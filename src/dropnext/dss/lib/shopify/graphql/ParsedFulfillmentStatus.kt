package dropnext.dss.lib.shopify.graphql

import dropnext.graphql.generated.enums.FulfillmentEventStatus


/**
 * Maps the status the monolith sends to Shopify's [FulfillmentEventStatus]. The monolith sends Shopify's own names in
 * snake case (`in_transit`, `ready_for_pickup`), which map one to one; spelling variants (`In Transit`, `IN-TRANSIT`)
 * and a few synonyms (`transit`, `pending`) are forgiven, so a change of wording on that side does not cost events.
 *
 * The [Known] / [Unknown] split exists so the handler can return `400 invalid request` for
 * an unsupported status instead of a `500` from a mid-flight enum throw:
 * the parser is the boundary that absorbs upstream string variation.
 */
sealed interface ParsedFulfillmentStatus {
  data class Known(val value: FulfillmentEventStatus) : ParsedFulfillmentStatus

  data class Unknown(val raw: String) : ParsedFulfillmentStatus


  companion object {
    fun parseFulfillmentEventStatus(raw: String): ParsedFulfillmentStatus {
      // Normalise to Shopify's `UPPER_SNAKE_CASE` enum form before the lookup, so a spelling variant still matches.
      val normalized = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
      // The generated `__UNKNOWN_VALUE` is what Shopify's answer decodes to for a status this build does not know.
      // Accepting it from the monolith would match any such event.
      val mapped = FulfillmentEventStatus.entries
        .filterNot { it == FulfillmentEventStatus.__UNKNOWN_VALUE }
        .find { it.name == normalized }
        ?: when (normalized) {
          "IN_TRANSIT", "TRANSIT" -> FulfillmentEventStatus.IN_TRANSIT
          "DELIVERED", "DELIVERY" -> FulfillmentEventStatus.DELIVERED
          "OUT_FOR_DELIVERY" -> FulfillmentEventStatus.OUT_FOR_DELIVERY
          "CONFIRMED", "PENDING", "INFO_RECEIVED" -> FulfillmentEventStatus.CONFIRMED
          "FAILURE", "FAILED" -> FulfillmentEventStatus.FAILURE
          else -> null
        }
      return if (mapped == null) Unknown(raw)
      else Known(mapped)
    }
  }
}
