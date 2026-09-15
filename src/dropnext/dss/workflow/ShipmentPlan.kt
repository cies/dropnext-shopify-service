package dropnext.dss.workflow

import dropnext.dss.contract.Shipment
import dropnext.dss.domain.fulfillment.SkippedShipmentLine
import dropnext.graphql.generated.getorderfordss.Fulfillment


/**
 * What one sync run has decided: the writes to make, and what it left out and why, so the caller can log the
 * skips without matching a second time.
 */
data class ShipmentPlan(
  val mutations: List<ShopifyMutation>,
  val skippedLines: List<SkippedShipmentLine>,
  /** The shipments whose tracking number is already on a live fulfillment of the order: a re-send, not a new package. */
  val alreadyFulfilledShipments: List<AlreadyFulfilledShipment>,
  /** The shipments none of whose lines matched, so no fulfillment is created for them. */
  val unmatchedShipments: List<Shipment>,
) {
  val skippedShipmentCount: Int get() = alreadyFulfilledShipments.size + unmatchedShipments.size
}

data class AlreadyFulfilledShipment(val shipment: Shipment, val fulfillments: List<Fulfillment>)
