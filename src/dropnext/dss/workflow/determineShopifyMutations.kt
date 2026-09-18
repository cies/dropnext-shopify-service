package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * Read-only: loads the order again when the run's cancels changed it, then [calculateShopifyMutations], and logs what
 * the plan left out. Shopify puts canceled units on fulfillment orders that may not have existed before the cancel, so
 * the creates cannot be planned against the order as it was.
 */
suspend fun determineShopifyMutations(
  shopifyGqlService: ShopifyGraphqlService,
  shopifyOrderId: ShopifyOrderId,
  shipments: List<Shipment>,
  aftermath: CancelAftermath,
  budget: ShipmentSyncBudget,
): ShopifyResult<ShipmentPlan> {
  // Without a shipment there is nothing to plan the reload for.
  val order = if (!aftermath.orderChanged || shipments.isEmpty()) {
    aftermath.orderBeforeCancels
  } else {
    if (!budget.allowsAnotherCall()) return Failure(budget.timedOut())
    when (val loaded = shopifyGqlService.orderForDss(orderGid(shopifyOrderId))) {
      is Failure -> return loaded
      is Success -> loaded.value
    }
  }
  val plan = calculateShopifyMutations(order, shipments, aftermath)
  logLeftOut(shopifyOrderId, shipments, plan)
  return Success(plan)
}

/**
 * One line per shipment the plan left out, in payload order. Keyed by tracking number, which the request validators
 * keep unique within one payload.
 */
private fun logLeftOut(shopifyOrderId: ShopifyOrderId, shipments: List<Shipment>, plan: ShipmentPlan) {
  val alreadyFulfilledByTracking = plan.alreadyFulfilledShipments.associateBy { it.shipment.trackingNumber }
  val skippedByTracking = plan.skippedShipments.associateBy { it.shipment.trackingNumber }
  val blockedByTracking = plan.blockedShipments.associateBy { it.shipment.trackingNumber }
  shipments.forEach { shipment ->
    alreadyFulfilledByTracking[shipment.trackingNumber]?.let { logAlreadyFulfilled(shopifyOrderId, it) }
    skippedByTracking[shipment.trackingNumber]?.let { logSkipped(shopifyOrderId, it) }
    blockedByTracking[shipment.trackingNumber]?.let { logBlocked(shopifyOrderId, it) }
  }
}

/**
 * A re-send is how the monolith recovers from a lost commit or a `502`, so one live fulfillment with the tracking
 * number is information.
 * Two are a duplicate someone made by hand, or one from before the skip existed, for a human to clean up.
 */
private fun logAlreadyFulfilled(orderId: ShopifyOrderId, skipped: AlreadyFulfilledShipment) {
  val line = "sync-shipments skipped shipment orderId=$orderId tracking=${skipped.shipment.trackingNumber} " +
    "reason=already_fulfilled fulfillmentIds=${skipped.fulfillments.map { legacyIdFromGid(it.id) }}"
  if (skipped.fulfillments.size == 1) log.info { line } else log.warn { line }
}

/**
 * A shipment without room is expected (a re-ship, an over-shipment, units fulfilled by hand); a variant the order does
 * not have is a data problem in the monolith.
 */
private fun logSkipped(orderId: ShopifyOrderId, skipped: SkippedShipment) {
  val shortLines = skipped.shortLines.joinToString(", ", "[", "]") {
    "variant=${it.productVariantId}:asked=${it.asked}:available=${it.available}"
  }
  val line = "sync-shipments skipped shipment orderId=$orderId tracking=${skipped.shipment.trackingNumber} " +
    "reason=${skipped.reason.logLabel()} short=$shortLines"
  when (skipped.reason) {
    SkipReason.NO_ROOM -> log.info { line }
    SkipReason.NOT_ON_ORDER -> log.warn { line }
  }
}

/** Why the stale fulfillment stayed is logged where that was decided; this line ties the shipment to it. */
private fun logBlocked(orderId: ShopifyOrderId, blocked: BlockedShipment) {
  log.warn {
    "sync-shipments skipped shipment orderId=$orderId tracking=${blocked.shipment.trackingNumber} " +
      "reason=blocked fulfillmentId=${legacyIdFromGid(blocked.fulfillment.id)}"
  }
}

private fun SkipReason.logLabel(): String = when (this) {
  SkipReason.NOT_ON_ORDER -> "not_on_order"
  SkipReason.NO_ROOM -> "no_room"
}
