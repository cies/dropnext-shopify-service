package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.dss.domain.fulfillment.SkippedShipmentLine
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.orderGid
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/** Read-only: loads Shopify data, then [calculateShopifyMutations], and logs what the plan left out. */
suspend fun determineShopifyMutations(
  shopifyGqlService: ShopifyGraphqlService,
  shopifyOrderId: ShopifyOrderId,
  shipments: List<Shipment>,
): ShopifyResult<ShipmentPlan> {
  val order = when (val loaded = shopifyGqlService.orderForDss(orderGid(shopifyOrderId))) {
    is Failure -> return loaded
    is Success -> loaded.value
  }
  val calculated = calculateShopifyMutations(order, shipments)
  if (calculated is Success) {
    logSkips(shopifyOrderId, shipments, calculated.value)
  }
  return calculated
}

/**
 * In payload order: each shipment's skipped lines, then the shipment itself when nothing in it matched. Keyed by
 * tracking number, which the request validators keep unique within one payload.
 */
private fun logSkips(shopifyOrderId: ShopifyOrderId, shipments: List<Shipment>, plan: ShipmentPlan) {
  val skippedLinesByTracking = plan.skippedLines.groupBy { it.trackingNumber }
  val unmatchedTrackingNumbers = plan.unmatchedShipments.map { it.trackingNumber }.toSet()
  shipments.forEach { shipment ->
    skippedLinesByTracking[shipment.trackingNumber].orEmpty().forEach { logSkippedLine(shopifyOrderId, it) }
    if (shipment.trackingNumber in unmatchedTrackingNumbers) {
      log.warn {
        "sync-shipments skipped shipment orderId=$shopifyOrderId " +
          "tracking=${shipment.trackingNumber} reason=all_lines_unmatched"
      }
    }
  }
}

private fun logSkippedLine(orderId: ShopifyOrderId, skipped: SkippedShipmentLine) {
  log.warn {
    "sync-shipments skipped line orderId=$orderId " +
      "tracking=${skipped.trackingNumber} variant=${skipped.productVariantId} " +
      "reason=${skipped.reason.logLabel()} qty=${skipped.quantity}"
  }
}

private fun SkipReason.logLabel(): String = when (this) {
  SkipReason.NO_OPEN_FO -> "no_open_fo"
  SkipReason.ZERO_REMAINING -> "zero_remaining"
}
