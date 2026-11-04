package dropnext.dss.lib.monolith.drift


/**
 * What a difference is about. [label] is the `kind=` value a log query is written against, [plural] the summary's
 * count field. Declared in the order a reader takes them in: the root first, because it explains a wall of path lines.
 */
enum class ContractElementKind(val label: String, val plural: String) {
  API_ROOT("api_root", "roots"),
  PATH("path", "paths"),
  WEBHOOK("webhook", "webhooks"),
  OPERATION("operation", "operations"),
  PARAMETER("parameter", "parameters"),
  REQUEST_BODY("request_body", "request_bodies"),
  RESPONSE("response", "responses"),
  SCHEMA("schema", "schemas"),
  PROPERTY("property", "properties"),
  ENUM_VALUE("enum", "enums"),
  REQUIRED("required", "required"),
  TYPE("type", "types"),
}

/** Which document has an element the other lacks. The copy is what the DSS compiled against. */
enum class ContractSide(val label: String) {
  MONOLITH_ONLY("monolith_only"),
  COPY_ONLY("copy_only"),
}

/**
 * One way the monolith's document and the checked-in copy disagree: an element only one of them has ([side]), or an
 * attribute both have with another value ([monolithValue], [copyValue]). [isBreaking] is whether the DSS, compiled
 * against the copy, can fail against the monolith because of it: something it relies on is gone or of another type,
 * a field's `required` changed either way, or an enum gained or lost a value.
 */
data class ContractDifference(
  val kind: ContractElementKind,
  val location: String?,
  val isBreaking: Boolean,
  val side: ContractSide? = null,
  val monolithValue: String? = null,
  val copyValue: String? = null,
) {
  val line: String
    get() = buildString {
      append("Contract drift kind=${kind.label}")
      location?.let { append(" location=${quotedIfNeeded(it)}") }
      side?.let { append(" side=${it.label}") }
      if (side == null) append(" monolith=${quotedIfNeeded(monolithValue ?: "none")} copy=${quotedIfNeeded(copyValue ?: "none")}")
      append(" breaking=$isBreaking")
    }
}

/** What a comparison found, in reading order. Empty is identical, as far as the contract goes. */
data class ContractComparison(val differences: List<ContractDifference>) {
  val isIdentical: Boolean get() = differences.isEmpty()
  val breakingCount: Int get() = differences.count { it.isBreaking }

  /** The one line to alert on: the true total even when only [shown] of the difference lines were written. */
  fun summaryLine(shown: Int): String = buildString {
    append("Contract drift summary differences=${differences.size} shown=$shown breaking=$breakingCount")
    val counts = differences.groupingBy { it.kind }.eachCount()
    ContractElementKind.entries.forEach { kind -> append(" ${kind.plural}=${counts[kind] ?: 0}") }
  }
}

/** A value with a space (`"DELETE /product-variants"`) is quoted, so a `key=value` parser reads it whole. */
private fun quotedIfNeeded(value: String): String =
  if (value.isEmpty() || value.any { it.isWhitespace() || it == '"' }) "\"${value.replace("\"", "\\\"")}\"" else value
