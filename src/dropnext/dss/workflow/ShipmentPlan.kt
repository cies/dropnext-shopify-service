package dropnext.dss.workflow

import dropnext.dss.contract.Shipment
import dropnext.dss.domain.fulfillment.SkippedShipmentLine


/**
 * What one sync run has decided: the writes to make, and what it left out and why, so the caller can log the
 * skips without matching a second time.
 */
data class ShipmentPlan(
  val mutations: List<ShopifyMutation>,
  val skippedLines: List<SkippedShipmentLine>,
  /** The shipments none of whose lines matched, so no fulfillment is created for them. */
  val unmatchedShipments: List<Shipment>,
)
