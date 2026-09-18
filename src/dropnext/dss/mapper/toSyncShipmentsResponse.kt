package dropnext.dss.mapper

import dropnext.dss.contract.FulfillmentCancellationEntry
import dropnext.dss.contract.FulfillmentCancellationOutcome
import dropnext.dss.contract.ShipmentSkipReason
import dropnext.dss.contract.ShipmentSyncEntry
import dropnext.dss.contract.ShipmentSyncOutcome
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsResponse
import dropnext.dss.domain.FulfillmentCancellation
import dropnext.dss.domain.FulfillmentCancellationStatus
import dropnext.dss.domain.ShipmentSync
import dropnext.dss.domain.ShipmentSyncReport
import dropnext.dss.domain.ShipmentSyncStatus
import dropnext.dss.domain.fulfillment.SkipReason


/** The report as the monolith reads it. Shopify's refusal messages and the notified count stay in the log. */
fun ShipmentSyncReport.toSyncShipmentsResponse(): SyncShipmentsWithFulfillmentsResponse =
  SyncShipmentsWithFulfillmentsResponse(
    shipments = shipments.map { it.toShipmentSyncEntry() },
    cancellations = cancellations.map { it.toFulfillmentCancellationEntry() },
  )

private fun ShipmentSync.toShipmentSyncEntry(): ShipmentSyncEntry = when (val s = status) {
  is ShipmentSyncStatus.Created -> entry(ShipmentSyncOutcome.CREATED, fulfillmentId = s.fulfillmentId.value)
  is ShipmentSyncStatus.AlreadyFulfilled ->
    entry(ShipmentSyncOutcome.ALREADY_FULFILLED, fulfillmentId = s.fulfillmentId?.value)
  is ShipmentSyncStatus.Skipped -> entry(ShipmentSyncOutcome.SKIPPED, skipReason = s.reason.toShipmentSkipReason())
  is ShipmentSyncStatus.Blocked -> entry(ShipmentSyncOutcome.BLOCKED, fulfillmentId = s.fulfillmentId?.value)
  is ShipmentSyncStatus.Refused -> entry(ShipmentSyncOutcome.REFUSED)
  ShipmentSyncStatus.Failed -> entry(ShipmentSyncOutcome.FAILED)
  ShipmentSyncStatus.NotAttempted -> entry(ShipmentSyncOutcome.NOT_ATTEMPTED)
}

private fun ShipmentSync.entry(
  outcome: ShipmentSyncOutcome,
  fulfillmentId: Long? = null,
  skipReason: ShipmentSkipReason? = null,
) = ShipmentSyncEntry(
  trackingNumber = trackingNumber,
  outcome = outcome,
  fulfillmentId = fulfillmentId,
  skipReason = skipReason,
)

private fun SkipReason.toShipmentSkipReason(): ShipmentSkipReason = when (this) {
  SkipReason.NO_ROOM -> ShipmentSkipReason.NO_ROOM
  SkipReason.NOT_ON_ORDER -> ShipmentSkipReason.NOT_ON_ORDER
}

private fun FulfillmentCancellation.toFulfillmentCancellationEntry(): FulfillmentCancellationEntry =
  FulfillmentCancellationEntry(
    trackingNumber = trackingNumber,
    fulfillmentId = fulfillmentId.value,
    outcome = when (status) {
      FulfillmentCancellationStatus.Canceled -> FulfillmentCancellationOutcome.CANCELED
      is FulfillmentCancellationStatus.Refused -> FulfillmentCancellationOutcome.REFUSED
      FulfillmentCancellationStatus.Failed -> FulfillmentCancellationOutcome.FAILED
      FulfillmentCancellationStatus.NotAttempted -> FulfillmentCancellationOutcome.NOT_ATTEMPTED
    },
  )
