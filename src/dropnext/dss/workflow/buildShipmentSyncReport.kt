package dropnext.dss.workflow

import dropnext.dss.contract.Shipment
import dropnext.dss.domain.FulfillmentCancellation
import dropnext.dss.domain.FulfillmentCancellationStatus
import dropnext.dss.domain.ShipmentSync
import dropnext.dss.domain.ShipmentSyncReport
import dropnext.dss.domain.ShipmentSyncStatus
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.graphql.generated.getorderfordss.Fulfillment


/**
 * Pure: the report of a run that got as far as a plan, from what the plan decided and what Shopify answered.
 *
 * The plan puts every shipment in exactly one of its lists under the tracking number as sent, which the request
 * validators keep unique, so each shipment is found by that string. [cancels] are the cancels the run sent, in that
 * order. A planned mutation Shopify did not answer is the one that stopped the run, or one after it.
 */
fun buildShipmentSyncReport(
  shipments: List<Shipment>,
  plan: ShipmentPlan,
  cancels: List<ShopifyMutation.FulfillmentCancel>,
  effected: MutationEffectReport,
): ShipmentSyncReport {
  val alreadyFulfilled = plan.alreadyFulfilledShipments.associateBy { it.shipment.trackingNumber }
  val skipped = plan.skippedShipments.associateBy { it.shipment.trackingNumber }
  val blocked = plan.blockedShipments.associateBy { it.shipment.trackingNumber }
  val creates = plan.mutations.filterIsInstance<ShopifyMutation.FulfillmentCreate>().associateBy { it.trackingNumber }
  val effects = effected.effects.associateBy { it.mutation }
  // A run the budget stopped between two mutations stopped on none of them.
  val stoppedAt = effected.stop?.mutation

  val shipmentSyncs = shipments.map { shipment ->
    val tracking = shipment.trackingNumber
    val status = alreadyFulfilled[tracking]?.let { ShipmentSyncStatus.AlreadyFulfilled(it.firstFulfillmentId()) }
      ?: skipped[tracking]?.let { ShipmentSyncStatus.Skipped(it.reason) }
      ?: blocked[tracking]?.let { ShipmentSyncStatus.Blocked(it.fulfillment.fulfillmentId()) }
      ?: creates[tracking]?.let { createStatus(effects[it], failed = it == stoppedAt) }
      ?: error("the shipments plan has no decision for tracking number $tracking")
    ShipmentSync(tracking, status)
  }
  val cancellations = cancels.map { cancel ->
    val status = cancelStatus(effects[cancel], failed = cancel == stoppedAt)
    FulfillmentCancellation(cancel.trackingNumber, cancel.fulfillmentId, status)
  }
  val notifiedCount = effected.effects.count { it is MutationEffect.Created && it.mutation.notifyCustomer }
  return ShipmentSyncReport(shipmentSyncs, cancellations, notifiedCount)
}

/**
 * The report of a run that stopped before it had a plan for its creates: nothing was classified, so nothing is claimed
 * about a shipment. [cancels] are the cancels the run did send before it stopped, with what Shopify answered.
 */
fun notAttemptedShipmentSyncReport(
  shipments: List<Shipment>,
  cancels: List<ShopifyMutation.FulfillmentCancel> = emptyList(),
  effected: MutationEffectReport = MutationEffectReport(emptyList()),
): ShipmentSyncReport {
  val emptyPlan = ShipmentPlan(emptyList(), emptyList(), emptyList(), emptyList())
  val report = buildShipmentSyncReport(emptyList(), emptyPlan, cancels, effected)
  return report.copy(shipments = shipments.map { ShipmentSync(it.trackingNumber, ShipmentSyncStatus.NotAttempted) })
}

private fun createStatus(effect: MutationEffect?, failed: Boolean): ShipmentSyncStatus = when {
  effect is MutationEffect.Created -> ShipmentSyncStatus.Created(effect.fulfillmentId)
  effect is MutationEffect.Refused -> ShipmentSyncStatus.Refused(effect.messages)
  failed -> ShipmentSyncStatus.Failed
  else -> ShipmentSyncStatus.NotAttempted
}

private fun cancelStatus(effect: MutationEffect?, failed: Boolean): FulfillmentCancellationStatus = when {
  effect is MutationEffect.Canceled -> FulfillmentCancellationStatus.Canceled
  effect is MutationEffect.Refused -> FulfillmentCancellationStatus.Refused(effect.messages)
  failed -> FulfillmentCancellationStatus.Failed
  else -> FulfillmentCancellationStatus.NotAttempted
}

/** Two live fulfillments with one number are a duplicate made by hand; the report names the first. */
private fun AlreadyFulfilledShipment.firstFulfillmentId(): ShopifyFulfillmentId? =
  fulfillments.firstNotNullOfOrNull { it.fulfillmentId() }

private fun Fulfillment.fulfillmentId(): ShopifyFulfillmentId? = legacyIdFromGid(id)?.let(::ShopifyFulfillmentId)
