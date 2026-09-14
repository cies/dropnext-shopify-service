package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.DryRunResult
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.dss.domain.fulfillment.SkippedShipmentLine
import dropnext.dss.domain.fulfillment.dryRunAllShipments
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.orderGid
import dropnext.graphql.generated.getorderfordss.Order
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/** Read-only: loads Shopify data, then [calculateShopifyMutations]. */
suspend fun determineShopifyMutations(
  shopifyGqlService: ShopifyGraphqlService,
  shopifyOrderId: ShopifyOrderId,
  shipments: List<Shipment>,
): ShopifyResult<List<ShopifyMutation>> {
  val order = when (val loaded = shopifyGqlService.orderForDss(orderGid(shopifyOrderId))) {
    is Failure -> return loaded
    is Success -> loaded.value
  }
  val calculated = calculateShopifyMutations(order, shipments)
  if (calculated is Success) {
    logSkippedShipmentLines(
      shopifyOrderId = shopifyOrderId,
      order = order,
      shipments = shipments,
    )
  }
  return calculated
}

private fun logSkippedShipmentLines(
  shopifyOrderId: ShopifyOrderId,
  order: Order,
  shipments: List<Shipment>,
) {
  when (val match = dryRunAllShipments(order, shipments)) {
    is DryRunResult.UserError -> return
    is DryRunResult.Ok -> {
      match.perShipment.forEachIndexed { index, shipmentMatch ->
        shipmentMatch.skipped.forEach { skipped ->
          logSkippedLine(shopifyOrderId, skipped)
        }
        if (shipmentMatch.groups.isEmpty()) {
          val tracking = shipments.getOrNull(index)?.trackingNumber.orEmpty()
          log.warn {
            "sync-shipments skipped shipment orderId=$shopifyOrderId " +
              "tracking=$tracking reason=all_lines_unmatched"
          }
        }
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
  SkipReason.VARIANT_NOT_FOUND -> "variant_not_found"
  SkipReason.ZERO_REMAINING -> "zero_remaining"
}
