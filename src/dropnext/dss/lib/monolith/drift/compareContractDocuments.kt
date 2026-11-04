package dropnext.dss.lib.monolith.drift

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull


/**
 * Compares the monolith's OpenAPI document with the checked-in copy over the parsed JSON, so key order and whitespace
 * do not matter. What is compared is what the generated code depends on: the API root the path keys share, the paths
 * and webhooks with their operations, parameters, request bodies and responses, the schemas with their properties,
 * types, nullability, required lists and enum values. Prose never is (descriptions, summaries, examples, the `info`
 * block), and neither are `servers` (http4k's default `/` on both sides) or the security schemes.
 *
 * An element only one side has is reported once, at the highest level that differs: a schema the monolith dropped is
 * one line, not one per property.
 */
fun compareContractDocuments(monolith: JsonObject, copy: JsonObject): ContractComparison {
  val monolithElements = contractElementsOf(monolith)
  val copyElements = contractElementsOf(copy)
  val differences = buildList {
    apiRootDifference(monolith, copy)?.let(::add)
    (monolithElements.keys + copyElements.keys).forEach { key ->
      val inMonolith = monolithElements[key]
      val inCopy = copyElements[key]
      when {
        inMonolith != null && inCopy != null -> addAll(attributeDifferences(inMonolith, inCopy))
        inMonolith != null -> presenceDifference(inMonolith, ContractSide.MONOLITH_ONLY, monolithElements, copyElements)?.let(::add)
        inCopy != null -> presenceDifference(inCopy, ContractSide.COPY_ONLY, monolithElements, copyElements)?.let(::add)
      }
    }
  }
  return ContractComparison(differences.sortedWith(compareBy({ it.kind.ordinal }, { it.location.orEmpty() })))
}

private data class ElementKey(val kind: ContractElementKind, val location: String)

/** One comparable element. [type] and [required] are `null` where the kind has no such attribute. */
private data class ContractElement(
  val key: ElementKey,
  val parent: ElementKey?,
  val type: String? = null,
  val required: Boolean? = null,
)

/** Reported only when its parent is on both sides; otherwise the parent's own line covers it. */
private fun presenceDifference(
  element: ContractElement,
  side: ContractSide,
  monolithElements: Map<ElementKey, ContractElement>,
  copyElements: Map<ElementKey, ContractElement>,
): ContractDifference? {
  val parent = element.parent
  if (parent != null && (parent !in monolithElements || parent !in copyElements)) return null
  // Something the copy has is something the DSS may rely on. Something only the monolith has is new, and breaks
  // nothing unless the monolith now requires it. An enum value breaks either way: the comparison does not know which
  // side decodes the enum, and a decoder without a case for a value fails on it.
  val isBreaking = side == ContractSide.COPY_ONLY || element.required == true || element.key.kind == ContractElementKind.ENUM_VALUE
  return ContractDifference(element.key.kind, element.key.location, isBreaking, side = side)
}

private fun attributeDifferences(inMonolith: ContractElement, inCopy: ContractElement): List<ContractDifference> = buildList {
  val location = inMonolith.key.location
  if (inMonolith.type != inCopy.type) {
    add(ContractDifference(ContractElementKind.TYPE, location, isBreaking = true, monolithValue = inMonolith.type, copyValue = inCopy.type))
  }
  if (inMonolith.required != inCopy.required) {
    // Breaking in both directions, because the comparison does not know which side writes the field. Newly required
    // breaks the side that sends it without; no longer required breaks the side that decodes it into a non-null
    // property, the first time the field is left out.
    add(
      ContractDifference(
        ContractElementKind.REQUIRED,
        location,
        isBreaking = true,
        monolithValue = inMonolith.required.toString(),
        copyValue = inCopy.required.toString(),
      ),
    )
  }
}

/**
 * The root the path keys share. The monolith bakes it into every key rather than into `servers`, so moving the API to
 * another root changes every path at once; this one line says why the path lines that follow it are there.
 */
private fun apiRootDifference(monolith: JsonObject, copy: JsonObject): ContractDifference? {
  val monolithRoot = apiRootOf(monolith) ?: return null
  val copyRoot = apiRootOf(copy) ?: return null
  if (monolithRoot == copyRoot) return null
  return ContractDifference(ContractElementKind.API_ROOT, null, isBreaking = true, monolithValue = monolithRoot, copyValue = copyRoot)
}

/**
 * The segments every path key starts with, never a whole key: with one path `/api/v1/orders` the root is `/api/v1`.
 * `null` for a document without paths, which the path lines describe on their own.
 */
private fun apiRootOf(document: JsonObject): String? {
  val segmentLists = document.objectAt("paths")?.keys?.map { key -> key.split('/').filter { it.isNotEmpty() } }
  if (segmentLists.isNullOrEmpty()) return null
  val shortest = segmentLists.minOf { it.size }
  val shared = (0 until (shortest - 1).coerceAtLeast(0))
    .takeWhile { index -> segmentLists.all { it[index] == segmentLists.first()[index] } }
    .map { segmentLists.first()[it] }
  return "/" + shared.joinToString("/")
}

private val httpMethods = listOf("get", "put", "post", "delete", "patch", "head", "options", "trace")

private fun contractElementsOf(document: JsonObject): Map<ElementKey, ContractElement> {
  val elements = mutableListOf<ContractElement>()
  document.objectAt("paths")?.forEach { (path, item) ->
    addRoute(elements, ElementKey(ContractElementKind.PATH, path), path, item)
  }
  document.objectAt("webhooks")?.forEach { (name, item) ->
    addRoute(elements, ElementKey(ContractElementKind.WEBHOOK, name), name, item)
  }
  document.objectAt("components")?.objectAt("schemas")?.forEach { (name, schema) ->
    val key = ElementKey(ContractElementKind.SCHEMA, name)
    elements += ContractElement(key, parent = null, type = typeOf(schema))
    addSchemaContent(elements, key, name, schema)
  }
  return elements.associateBy { it.key }
}

private fun addRoute(elements: MutableList<ContractElement>, routeKey: ElementKey, route: String, item: JsonElement) {
  elements += ContractElement(routeKey, parent = null)
  val operations = item as? JsonObject ?: return
  httpMethods.forEach { method ->
    val operation = operations.objectAt(method) ?: return@forEach
    val operationName = "${method.uppercase()} $route"
    val operationKey = ElementKey(ContractElementKind.OPERATION, operationName)
    elements += ContractElement(operationKey, parent = routeKey)
    (operation["parameters"] as? JsonArray)?.forEach { parameter ->
      val fields = parameter as? JsonObject ?: return@forEach
      val location = "$operationName ${fields.stringAt("in")} ${fields.stringAt("name")}"
      elements += ContractElement(
        ElementKey(ContractElementKind.PARAMETER, location),
        parent = operationKey,
        type = typeOf(fields["schema"]),
        required = fields.booleanAt("required") ?: false,
      )
    }
    operation.objectAt("requestBody")?.let { body ->
      elements += ContractElement(
        ElementKey(ContractElementKind.REQUEST_BODY, operationName),
        parent = operationKey,
        type = contentTypeOf(body),
        required = body.booleanAt("required") ?: false,
      )
    }
    operation.objectAt("responses")?.forEach { (status, response) ->
      elements += ContractElement(
        ElementKey(ContractElementKind.RESPONSE, "$operationName $status"),
        parent = operationKey,
        type = (response as? JsonObject)?.let(::contentTypeOf),
      )
    }
  }
}

/** The properties and enum values under [owner], at any depth an inline schema nests them. */
private fun addSchemaContent(elements: MutableList<ContractElement>, owner: ElementKey, location: String, schema: JsonElement?) {
  val fields = schema as? JsonObject ?: return
  (fields["enum"] as? JsonArray)?.forEach { value ->
    val text = (value as? JsonPrimitive)?.contentOrNull ?: "null"
    elements += ContractElement(ElementKey(ContractElementKind.ENUM_VALUE, "$location.$text"), parent = owner)
  }
  val required = (fields["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
  fields.objectAt("properties")?.forEach { (name, propertySchema) ->
    val propertyLocation = "$location.$name"
    val key = ElementKey(ContractElementKind.PROPERTY, propertyLocation)
    elements += ContractElement(key, parent = owner, type = typeOf(propertySchema), required = name in required)
    addSchemaContent(elements, key, propertyLocation, propertySchema)
  }
  addSchemaContent(elements, owner, "$location[]", fields["items"])
}

/** The JSON body's schema, by media type when there are several: what the generated code reads or writes. */
private fun contentTypeOf(bodyOrResponse: JsonObject): String? {
  val content = bodyOrResponse.objectAt("content") ?: return null
  return content.entries
    .sortedBy { it.key }
    .joinToString(" ") { (mediaType, media) ->
      val type = typeOf((media as? JsonObject)?.get("schema")) ?: "any"
      if (content.size == 1) type else "$mediaType:$type"
    }
}

/**
 * A schema's type as one comparable string: a `$ref` by the schema it names, an array by its items, a format beside
 * its type, nullability as `|null` (OpenAPI 3.1 spells it as a type list or an `anyOf` with `null`, 3.0 as `nullable`).
 * Enum values are compared on their own, so they are not part of it.
 */
private fun typeOf(schema: JsonElement?): String? {
  val fields = schema as? JsonObject ?: return null
  fields.stringAt("\$ref")?.let { return it.substringAfterLast('/') }
  listOf("anyOf" to "|", "oneOf" to "|", "allOf" to "&").forEach { (combinator, separator) ->
    val members = fields[combinator] as? JsonArray ?: return@forEach
    return members.map { typeOf(it) ?: "any" }.sortedWith(nullLast).joinToString(separator)
  }
  val declared = when (val type = fields["type"]) {
    is JsonPrimitive -> listOfNotNull(type.contentOrNull)
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
  }
  val nullable = fields.booleanAt("nullable") == true
  val types = declared.ifEmpty { if (fields.objectAt("properties") != null) listOf("object") else listOf("any") }
  return (types + if (nullable) listOf("null") else emptyList())
    .distinct()
    .map { type -> describedType(type, fields) }
    .sortedWith(nullLast)
    .joinToString("|")
}

private fun describedType(type: String, fields: JsonObject): String = when {
  type == "null" -> type
  type == "array" -> "array<${typeOf(fields["items"]) ?: "any"}>"
  fields.stringAt("format") != null -> "$type(${fields.stringAt("format")})"
  else -> type
}

private val nullLast = compareBy<String>({ it == "null" }, { it })

private fun JsonObject.objectAt(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.stringAt(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanAt(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
