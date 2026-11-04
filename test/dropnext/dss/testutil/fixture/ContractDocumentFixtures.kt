package dropnext.dss.testutil.fixture

import dropnext.dss.lib.json.MonolithJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject


/**
 * A small OpenAPI document, in the shape the monolith renders: the API root baked into every path key, schemas under
 * `components`. Each argument is the inside of its JSON object, so a case states only the part it is about.
 */
internal fun contractDocument(
  paths: String = "",
  schemas: String = "",
  webhooks: String = "",
  info: String = """"title":"DropNext DSS API","version":"1"""",
  servers: String = """{"url":"/","description":""}""",
): JsonObject = MonolithJson.parseToJsonElement(
  """
    {
      "openapi": "3.1.0",
      "info": {$info},
      "paths": {$paths},
      "webhooks": {$webhooks},
      "components": {"schemas": {$schemas}},
      "servers": [$servers]
    }
  """,
).jsonObject

/** The orders route as the copy has it: one operation, a required body, two answers. */
internal val ORDERS_PATH: String = """
  "/api/shopify-service/v1/orders": {
    "post": {
      "summary": "Create an order",
      "parameters": [],
      "requestBody": {"required": true, "content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/Order"}}}},
      "responses": {
        "200": {"description": "OK", "content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/Order"}}}},
        "409": {"description": "Already exists."}
      }
    }
  }
"""

/** A store lookup, so a document has two paths and its root is not the whole of either key. */
internal val STORES_PATH: String = """
  "/api/shopify-service/v1/stores": {
    "get": {
      "parameters": [{"schema": {"type": "string"}, "in": "query", "name": "shopify_subdomain", "required": true}],
      "responses": {"200": {"description": "OK"}}
    }
  }
"""

/** An order with a required quantity, an optional nullable email and an enum status. */
internal val ORDER_SCHEMAS: String = """
  "Order": {
    "type": "object",
    "required": ["quantity", "status"],
    "properties": {
      "quantity": {"type": "integer", "format": "int32", "description": "How many."},
      "email": {"type": ["string", "null"]},
      "status": {"${'$'}ref": "#/components/schemas/OrderStatus"}
    }
  },
  "OrderStatus": {"type": "string", "enum": ["open", "closed"]}
"""
