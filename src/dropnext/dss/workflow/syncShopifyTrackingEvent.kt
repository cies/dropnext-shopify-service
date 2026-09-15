package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.domain.ShopifyFulfillmentEventId
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.liveFulfillmentsWithTrackingNumber
import dropnext.dss.lib.shopify.graphql.ParsedFulfillmentStatus
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.orderGid


/**
 * Looks up the live Shopify fulfillment by order and tracking number, then creates a `FulfillmentEvent` in Shopify.
 * Composes [ShopifyGraphqlService.orderForDss] and [ShopifyGraphqlService.createFulfillmentEvent].
 */
suspend fun syncShopifyTrackingEvent(
  shopify: ShopifyGraphqlService,
  payload: TrackingUpdateRequest,
): ShopifyResult<ShopifyFulfillmentEventId> {
  val status = when (val parsed = ParsedFulfillmentStatus.parseFulfillmentEventStatus(payload.status)) {
    is ParsedFulfillmentStatus.Known -> parsed.value
    is ParsedFulfillmentStatus.Unknown ->
      return Failure(ShopifyError.UserError(listOf("unsupported tracking status: ${parsed.raw}")))
  }
  val shopifyOrderId = ShopifyOrderId(payload.shopifyOrderId)
  val order = when (val loaded = shopify.orderForDss(orderGid(shopifyOrderId))) {
    is Failure -> return loaded
    is Success -> loaded.value
  }
  val fulfillmentGid = liveFulfillmentsWithTrackingNumber(order, payload.trackingNumber).firstOrNull()?.id
    ?: return Failure(
      ShopifyError.NotFound("no fulfillment with tracking number ${payload.trackingNumber} on order $shopifyOrderId"),
    )
  return shopify.createFulfillmentEvent(
    fulfillmentGid = fulfillmentGid,
    status = status,
    happenedAt = payload.happenedAt,
    message = payload.message,
  )
}
