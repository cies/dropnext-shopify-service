package dropnext.dss.testutil.fixture

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject


/** The scope Shopify names when the grant lacks what a fulfillment create needs. */
internal const val FULFILLMENT_ORDERS_SCOPE: String = "write_merchant_managed_fulfillment_orders"

/** Shopify's wording for a field the grant does not reach, as it has read since 2020-07. */
internal fun accessDeniedMessage(field: String = "fulfillmentOrders", scope: String = FULFILLMENT_ORDERS_SCOPE): String =
  "Access denied for $field field. Required access: `$scope` access scope."

/**
 * The envelope Shopify answers with HTTP `200` for an operation the grant does not reach: no data, and one top-level
 * error per refused field, each carrying its `extensions.code`.
 */
internal fun graphqlErrorEnvelope(vararg errors: Pair<String, String>): String =
  buildJsonObject {
    put("data", JsonNull)
    put(
      "errors",
      buildJsonArray {
        errors.forEach { (message, code) ->
          add(
            buildJsonObject {
              put("message", message)
              putJsonObject("extensions") {
                put("code", code)
                if (code == "ACCESS_DENIED") put("documentation", "https://shopify.dev/api/usage/access-scopes")
              }
            },
          )
        }
      },
    )
  }.toString()

/** A single `ACCESS_DENIED` refusal naming [FULFILLMENT_ORDERS_SCOPE] unless the test passes another message. */
internal fun accessDeniedEnvelope(message: String = accessDeniedMessage()): String =
  graphqlErrorEnvelope(message to "ACCESS_DENIED")
