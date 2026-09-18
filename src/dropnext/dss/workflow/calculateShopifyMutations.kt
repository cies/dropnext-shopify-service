package dropnext.dss.workflow

import dropnext.dss.contract.Shipment
import dropnext.dss.domain.fulfillment.ShipmentMatchResult
import dropnext.dss.domain.fulfillment.dryRunAllShipments
import dropnext.dss.domain.fulfillment.fulfillmentsWithTrackingNumber
import dropnext.dss.domain.fulfillment.trackingNumbers
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.graphql.generated.getorderfordss.Order


/**
 * Pure: only works on its input values with no side effects. Plans the creates against [order], the order as it is
 * after the run's cancels, and never refuses: a shipment that does not fit is skipped.
 *
 * A shipment whose number is on a fulfillment [aftermath] names as blocking is not planned, so no number ends up on two
 * live fulfillments. The shipments the order has carried under their number are matched first, whatever became of that
 * fulfillment: Shopify has shown their package to the customer, and a shipment that never fit must not take the room a
 * cancel gave back. Within each group the request order decides.
 *
 * A create notifies the customer when the order had never carried its tracking number before the run, so a fulfillment
 * re-created under a number the customer already had an email for sends no second one.
 */
fun calculateShopifyMutations(
  order: Order,
  shipments: List<Shipment>,
  aftermath: CancelAftermath,
): ShipmentPlan {
  val blockedByNumber = aftermath.blockingFulfillments
    .flatMap { fulfillment -> fulfillment.trackingNumbers().map { it to fulfillment } }
    .toMap()
  val notifyAgainst = aftermath.orderBeforeCancels
  val (blocked, unblocked) = shipments.partition { it.trackingNumber.trim() in blockedByNumber }
  val (carriedBefore, others) = unblocked.partition {
    fulfillmentsWithTrackingNumber(notifyAgainst, it.trackingNumber).isNotEmpty()
  }
  val matchOrder = carriedBefore + others
  val matchByShipment = matchOrder
    .zip(dryRunAllShipments(order, matchOrder, aftermath.canceledFulfillmentIds))
    .toMap()
  return ShipmentPlan(
    mutations = matchOrder.mapNotNull { shipment ->
      (matchByShipment.getValue(shipment) as? ShipmentMatchResult.Matched)?.let {
        val notifyCustomer = fulfillmentsWithTrackingNumber(notifyAgainst, shipment.trackingNumber).isEmpty()
        fulfillmentCreate(shipment, it, notifyCustomer)
      }
    },
    alreadyFulfilledShipments = unblocked.mapNotNull { shipment ->
      (matchByShipment.getValue(shipment) as? ShipmentMatchResult.AlreadyFulfilled)
        ?.let { AlreadyFulfilledShipment(shipment, it.fulfillments) }
    },
    skippedShipments = unblocked.mapNotNull { shipment ->
      (matchByShipment.getValue(shipment) as? ShipmentMatchResult.Skipped)
        ?.let { SkippedShipment(shipment, it.reason, it.shortLines) }
    },
    blockedShipments = blocked.map { BlockedShipment(it, blockedByNumber.getValue(it.trackingNumber.trim())) },
  )
}

private fun fulfillmentCreate(
  shipment: Shipment,
  match: ShipmentMatchResult.Matched,
  notifyCustomer: Boolean,
): ShopifyMutation.FulfillmentCreate =
  ShopifyMutation.FulfillmentCreate(
    lineItems = match.groups.flatMap { (fulfillmentOrderId, inputs) ->
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
    notifyCustomer = notifyCustomer,
  )
