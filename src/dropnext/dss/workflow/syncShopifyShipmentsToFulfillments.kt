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
 * fulfillment order line items and live remaining quantity.
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
  val mutations = when (val determined = determineShopifyMutations(shopifyGqlService, shopifyOrderId, payload.shipments)) {
    is Failure -> return determined
    is Success -> determined.value.mutations
  }
  val effected = effectShopifyMutations(shopifyGqlService, mutations)
  if (effected is Success) {
    // One summary line per run, so a run can be found by order and shop and read off in Logflare.
    val canceled = mutations.count { it is ShopifyMutation.FulfillmentCancel }
    val skippedShipments = payload.shipments.size - mutations.count { it is ShopifyMutation.FulfillmentCreate }
    log.info {
      "sync-shipments orderId=$shopifyOrderId " +
        "canceled=$canceled created=${effected.value.size} skippedShipments=$skippedShipments " +
        "fulfillmentIds=${effected.value.map { it.value }}"
    }
  }
  return effected
}
