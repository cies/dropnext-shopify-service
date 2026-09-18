package dropnext.dss.workflow

import dropnext.dss.contract.Shipment
import dropnext.dss.domain.fulfillment.ShortLine
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.graphql.generated.getorderfordss.Fulfillment


/**
 * What one sync run has decided: the writes to make, in the order they are sent, and which shipments it left out and
 * why, so the caller can log them without matching a second time. The lists of shipments are in payload order, and each
 * shipment is in exactly one of them or has a create in [mutations].
 */
data class ShipmentPlan(
  val mutations: List<ShopifyMutation>,
  /** The shipments whose number is already on a live fulfillment of the order: a re-send, not a new package. */
  val alreadyFulfilledShipments: List<AlreadyFulfilledShipment>,
  /** The shipments that do not fit whole, so no fulfillment is created for them. */
  val skippedShipments: List<SkippedShipment>,
  /** The shipments whose tracking number is on a stale fulfillment this run did not cancel. */
  val blockedShipments: List<BlockedShipment>,
)

data class AlreadyFulfilledShipment(val shipment: Shipment, val fulfillments: List<Fulfillment>)

data class SkippedShipment(val shipment: Shipment, val reason: SkipReason, val shortLines: List<ShortLine>)

data class BlockedShipment(val shipment: Shipment, val fulfillment: Fulfillment)
