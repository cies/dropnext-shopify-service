package dropnext.dss.lib.monolith.drift

import dropnext.dss.lib.monolith.drift.ContractElementKind.API_ROOT
import dropnext.dss.lib.monolith.drift.ContractElementKind.ENUM_VALUE
import dropnext.dss.lib.monolith.drift.ContractElementKind.OPERATION
import dropnext.dss.lib.monolith.drift.ContractElementKind.PATH
import dropnext.dss.lib.monolith.drift.ContractElementKind.PROPERTY
import dropnext.dss.lib.monolith.drift.ContractElementKind.REQUIRED
import dropnext.dss.lib.monolith.drift.ContractElementKind.SCHEMA
import dropnext.dss.lib.monolith.drift.ContractElementKind.TYPE
import dropnext.dss.lib.monolith.drift.ContractSide.COPY_ONLY
import dropnext.dss.lib.monolith.drift.ContractSide.MONOLITH_ONLY
import dropnext.dss.testutil.fixture.ORDERS_PATH
import dropnext.dss.testutil.fixture.ORDER_SCHEMAS
import dropnext.dss.testutil.fixture.STORES_PATH
import dropnext.dss.testutil.fixture.contractDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test


/**
 * The comparison is the only part of the drift check worth real coverage: what a reader acts on is the kind and the
 * location of each line, and whether the build fails on it. Every case states the copy and the monolith's document
 * as small documents that differ in the one way the case is about.
 */
class CompareContractDocumentsTest {

  private val copy = contractDocument(paths = "$ORDERS_PATH,$STORES_PATH", schemas = ORDER_SCHEMAS)

  private fun monolith(
    paths: String = "$ORDERS_PATH,$STORES_PATH",
    schemas: String = ORDER_SCHEMAS,
    info: String = """"title":"DropNext DSS API","version":"1"""",
    servers: String = """{"url":"/","description":""}""",
  ) = contractDocument(paths = paths, schemas = schemas, info = info, servers = servers)

  private fun differencesAgainstCopy(monolith: JsonObject) = compareContractDocuments(monolith = monolith, copy = copy).differences

  // ---------- what is not a difference ----------

  @Test
  fun `identical documents are identical`() {
    assert(compareContractDocuments(monolith = monolith(), copy = copy).isIdentical)
  }

  @Test
  fun `the checked-in copy is identical to itself`() {
    val checkedIn = readCheckedInContractCopy()
    assert(compareContractDocuments(monolith = checkedIn, copy = checkedIn).isIdentical)
  }

  @Test
  fun `keys in another order are not a difference`() {
    val checkedIn = readCheckedInContractCopy()
    assert(compareContractDocuments(monolith = reversedKeys(checkedIn) as JsonObject, copy = checkedIn).isIdentical)
  }

  /** Prose the monolith edits freely; a check that fires on a reworded sentence is a check nobody reads. */
  @Test
  fun `descriptions, summaries and the info block are not a difference`() {
    val reworded = monolith(
      paths = "$ORDERS_PATH,$STORES_PATH".replace("Create an order", "Make an order").replace("Already exists.", "Duplicate."),
      schemas = ORDER_SCHEMAS.replace("How many.", "The count."),
      info = """"title":"Another title","version":"2"""",
    )
    assert(differencesAgainstCopy(reworded).isEmpty())
  }

  @Test
  fun `a servers entry is not a difference`() {
    assert(differencesAgainstCopy(monolith(servers = """{"url":"https://monolith.example.org"}""")).isEmpty())
  }

  /** OpenAPI 3.0 and 3.1 spell nullability differently; the generated code reads both the same way. */
  @Test
  fun `nullable spelled the 3_0 way is the same type as a type list with null`() {
    val spelledOtherwise = monolith(schemas = ORDER_SCHEMAS.replace("""{"type": ["string", "null"]}""", """{"type": "string", "nullable": true}"""))
    assert(differencesAgainstCopy(spelledOtherwise).isEmpty())
  }

  // ---------- paths and operations ----------

  @Test
  fun `an added path is a monolith-only path and breaks nothing`() {
    val added = """"/api/shopify-service/v1/added": {"get": {"responses": {"200": {"description": "OK"}}}}"""
    val differences = differencesAgainstCopy(monolith(paths = "$ORDERS_PATH,$STORES_PATH,$added"))
    val difference = differences.single()
    assert(difference.kind == PATH)
    assert(difference.location == "/api/shopify-service/v1/added")
    assert(difference.side == MONOLITH_ONLY)
    assert(!difference.isBreaking)
  }

  /** The path's operations, parameters and answers went with it; one line says so. */
  @Test
  fun `a removed path is one copy-only line and breaks the build`() {
    val withStoresOnly = contractDocument(paths = STORES_PATH, schemas = ORDER_SCHEMAS)
    val difference = compareContractDocuments(monolith = withStoresOnly, copy = copy).differences.single()
    assert(difference.kind == PATH)
    assert(difference.location == "/api/shopify-service/v1/orders")
    assert(difference.side == COPY_ONLY)
    assert(difference.isBreaking)
  }

  @Test
  fun `a removed operation is copy-only and breaks the build, an added one does not`() {
    val differences = differencesAgainstCopy(monolith(paths = "$ORDERS_PATH,${STORES_PATH.replace("\"get\"", "\"put\"")}"))
    val removed = differences.single { it.side == COPY_ONLY }
    assert(removed.kind == OPERATION)
    assert(removed.location == "GET /api/shopify-service/v1/stores")
    assert(removed.isBreaking)
    val added = differences.single { it.side == MONOLITH_ONLY }
    assert(added.kind == OPERATION)
    assert(added.location == "PUT /api/shopify-service/v1/stores")
    assert(!added.isBreaking)
  }

  /** The monolith moved its API: one line says why every path line that follows it is there. */
  @Test
  fun `path keys under another root are one api_root line beside the path lines`() {
    val moved = monolith(paths = "$ORDERS_PATH,$STORES_PATH".replace("/api/shopify-service/v1/", "/api/shopify-service/v2/"))
    val differences = differencesAgainstCopy(moved)
    val root = differences.single { it.kind == API_ROOT }
    assert(root.monolithValue == "/api/shopify-service/v2")
    assert(root.copyValue == "/api/shopify-service/v1")
    assert(root.isBreaking)
    assert(differences.first() == root)
    assert(differences.count { it.kind == PATH && it.side == MONOLITH_ONLY } == 2)
    assert(differences.count { it.kind == PATH && it.side == COPY_ONLY } == 2)
  }

  // ---------- schemas and properties ----------

  @Test
  fun `a field that became required is a required line and breaks the build`() {
    val difference = differencesAgainstCopy(monolith(schemas = ORDER_SCHEMAS.replace("""["quantity", "status"]""", """["quantity", "status", "email"]"""))).single()
    assert(difference.kind == REQUIRED)
    assert(difference.location == "Order.email")
    assert(difference.monolithValue == "true")
    assert(difference.copyValue == "false")
    assert(difference.isBreaking)
  }

  /**
   * Breaking in either direction, because the comparison does not know which side reads the field: in a body the DSS
   * decodes, the generated property is non-null without a default, and the first answer that leaves it out fails.
   */
  @Test
  fun `a field that is no longer required is a required line and breaks the build`() {
    val difference = differencesAgainstCopy(monolith(schemas = ORDER_SCHEMAS.replace("""["quantity", "status"]""", """["status"]"""))).single()
    assert(difference.kind == REQUIRED)
    assert(difference.location == "Order.quantity")
    assert(difference.monolithValue == "false")
    assert(difference.copyValue == "true")
    assert(difference.isBreaking)
  }

  @Test
  fun `a parameter that is no longer required breaks the build`() {
    val optionalParameter = STORES_PATH.replace("\"required\": true", "\"required\": false")
    val withOptional = contractDocument(paths = "$ORDERS_PATH,$optionalParameter", schemas = ORDER_SCHEMAS)
    val difference = differencesAgainstCopy(withOptional).single()
    assert(difference.kind == REQUIRED)
    assert(difference.location == "GET /api/shopify-service/v1/stores query shopify_subdomain")
    assert(difference.isBreaking)
  }

  @Test
  fun `a property of another type is a type line naming both and breaks the build`() {
    val retyped = monolith(schemas = ORDER_SCHEMAS.replace(""""type": "integer", "format": "int32"""", """"type": "string""""))
    val difference = differencesAgainstCopy(retyped).single()
    assert(difference.kind == TYPE)
    assert(difference.location == "Order.quantity")
    assert(difference.monolithValue == "string")
    assert(difference.copyValue == "integer(int32)")
    assert(difference.isBreaking)
  }

  @Test
  fun `a property that is no longer nullable is a type change`() {
    val difference = differencesAgainstCopy(monolith(schemas = ORDER_SCHEMAS.replace("""["string", "null"]""", "\"string\""))).single()
    assert(difference.kind == TYPE)
    assert(difference.location == "Order.email")
    assert(difference.monolithValue == "string")
    assert(difference.copyValue == "string|null")
  }

  /** The additive change either side can deploy first: a nullable, optional field the DSS does not send yet. */
  @Test
  fun `an added optional property breaks nothing, an added required one breaks the build`() {
    val optional = ORDER_SCHEMAS.replace(""""email": {""", """"note": {"type": ["string", "null"]}, "email": {""")
    val optionalDifference = differencesAgainstCopy(monolith(schemas = optional)).single()
    assert(optionalDifference.kind == PROPERTY)
    assert(optionalDifference.location == "Order.note")
    assert(optionalDifference.side == MONOLITH_ONLY)
    assert(!optionalDifference.isBreaking)

    val required = optional.replace("""["quantity", "status"]""", """["quantity", "status", "note"]""")
    assert(differencesAgainstCopy(monolith(schemas = required)).single().isBreaking)
  }

  @Test
  fun `a removed property is copy-only and breaks the build`() {
    val difference = differencesAgainstCopy(monolith(schemas = ORDER_SCHEMAS.replace(""""email": {"type": ["string", "null"]},""", ""))).single()
    assert(difference.kind == PROPERTY)
    assert(difference.location == "Order.email")
    assert(difference.side == COPY_ONLY)
    assert(difference.isBreaking)
  }

  @Test
  fun `a removed schema is one line, not one per property`() {
    val withoutStatus = contractDocument(paths = "$ORDERS_PATH,$STORES_PATH", schemas = """"Order": {"type": "object"}""")
    val differences = compareContractDocuments(monolith = withoutStatus, copy = copy).differences
    val removedSchema = differences.single { it.kind == SCHEMA }
    assert(removedSchema.location == "OrderStatus")
    assert(removedSchema.side == COPY_ONLY)
    assert(differences.none { it.kind == ENUM_VALUE })
  }

  /** An added value breaks too: in a body the DSS decodes, the generated enum has no case for it and the decode fails. */
  @Test
  fun `a removed enum value and an added one both break the build`() {
    val differences = differencesAgainstCopy(monolith(schemas = ORDER_SCHEMAS.replace("""["open", "closed"]""", """["open", "archived"]""")))
    val removed = differences.single { it.side == COPY_ONLY }
    assert(removed.kind == ENUM_VALUE)
    assert(removed.location == "OrderStatus.closed")
    assert(removed.isBreaking)
    val added = differences.single { it.side == MONOLITH_ONLY }
    assert(added.kind == ENUM_VALUE)
    assert(added.location == "OrderStatus.archived")
    assert(added.isBreaking)
  }

  // ---------- operations' parts ----------

  @Test
  fun `a parameter that became required breaks the build`() {
    val optionalParameter = STORES_PATH.replace("\"required\": true", "\"required\": false")
    val withOptional = contractDocument(paths = "$ORDERS_PATH,$optionalParameter", schemas = ORDER_SCHEMAS)
    val difference = compareContractDocuments(monolith = copy, copy = withOptional).differences.single()
    assert(difference.kind == REQUIRED)
    assert(difference.location == "GET /api/shopify-service/v1/stores query shopify_subdomain")
    assert(difference.isBreaking)
  }

  @Test
  fun `a response body of another schema is a type change on the response`() {
    val otherBody = ORDERS_PATH.replace(
      """"200": {"description": "OK", "content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/Order"}}}}""",
      """"200": {"description": "OK", "content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/OrderStatus"}}}}""",
    )
    val difference = differencesAgainstCopy(monolith(paths = "$otherBody,$STORES_PATH")).single()
    assert(difference.kind == TYPE)
    assert(difference.location == "POST /api/shopify-service/v1/orders 200")
    assert(difference.monolithValue == "OrderStatus")
    assert(difference.copyValue == "Order")
  }

  // ---------- helpers ----------

  private fun reversedKeys(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.reversed().associate { (key, value) -> key to reversedKeys(value) })
    is JsonArray -> JsonArray(element.map(::reversedKeys))
    else -> element
  }
}
