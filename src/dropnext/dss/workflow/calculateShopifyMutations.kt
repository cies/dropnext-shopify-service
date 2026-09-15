package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.domain.fulfillment.DryRunResult
import dropnext.dss.domain.fulfillment.ShipmentMatchResult
import dropnext.dss.domain.fulfillment.dryRunAllShipments
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.graphql.generated.getorderfordss.Order


/** Pure: only works on its input values with no side effects. */
fun calculateShopifyMutations(
  order: Order,
  shipments: List<Shipment>,
): ShopifyResult<ShipmentPlan> =
  when (val match = dryRunAllShipments(order, shipments)) {
    is DryRunResult.UserError -> Failure(ShopifyError.UserError(match.messages))
    is DryRunResult.Ok -> {
      val matched = shipments.zip(match.perShipment)
      Success(
        ShipmentPlan(
          mutations = matched.mapNotNull { (shipment, shipmentMatch) -> fulfillmentCreate(shipment, shipmentMatch) },
          skippedLines = match.perShipment.flatMap { it.skipped },
          alreadyFulfilledShipments = matched
            .filter { (_, shipmentMatch) -> shipmentMatch.alreadyFulfilledBy.isNotEmpty() }
            .map { (shipment, shipmentMatch) -> AlreadyFulfilledShipment(shipment, shipmentMatch.alreadyFulfilledBy) },
          unmatchedShipments = matched
            .filter { (_, shipmentMatch) -> shipmentMatch.groups.isEmpty() && shipmentMatch.alreadyFulfilledBy.isEmpty() }
            .map { it.first },
        ),
      )
    }
  }

private fun fulfillmentCreate(shipment: Shipment, shipmentMatch: ShipmentMatchResult.Ok): ShopifyMutation.FulfillmentCreate? {
  if (shipmentMatch.groups.isEmpty()) return null
  return ShopifyMutation.FulfillmentCreate(
    lineItems = shipmentMatch.groups.flatMap { (fulfillmentOrderId, inputs) ->
      inputs.map { input ->
        FulfillmentLine(
          fulfillmentOrderId = fulfillmentOrderId,
          lineItemId = input.id,
          quantity = input.quantity,
        )
      }
    },
    trackingNumber = shipment.trackingNumber,
    carrier = shipment.carrier,
    trackingUrl = shipment.trackingUrl,
    notifyCustomer = false,
  )
}
