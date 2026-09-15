package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Creates Shopify fulfillments from the supplied shipments without canceling existing ones.
 * Fulfillment orders are resolved automatically by matching `product_variant_id` against
 * fulfillment order line items and live remaining quantity. A shipment whose tracking number is already
 * on a live fulfillment of the order is skipped, so a re-sent payload creates nothing twice.
 *
 * Returns the ids of the created fulfillments.
 *
 * Composes [determineShopifyMutations] (read-only) and [effectShopifyMutations] (creates).
 */
suspend fun syncShopifyShipmentsToFulfillments(
  shopifyGqlService: ShopifyGraphqlService,
  payload: SyncShipmentsWithFulfillmentsRequest,
): ShopifyResult<List<ShopifyFulfillmentId>> {
  val shopifyOrderId = ShopifyOrderId(payload.shopifyOrderId)
  val plan = when (val determined = determineShopifyMutations(shopifyGqlService, shopifyOrderId, payload.shipments)) {
    is Failure -> return determined
    is Success -> determined.value
  }
  val effected = effectShopifyMutations(shopifyGqlService, plan.mutations)
  if (effected is Success) {
    // One summary line per run, so a run can be found by order and shop and read off in Logflare.
    val canceled = plan.mutations.count { it is ShopifyMutation.FulfillmentCancel }
    log.info {
      "sync-shipments orderId=$shopifyOrderId " +
        "canceled=$canceled created=${effected.value.size} skippedShipments=${plan.skippedShipmentCount} " +
        "fulfillmentIds=${effected.value.map { it.value }}"
    }
  }
  return effected
}
