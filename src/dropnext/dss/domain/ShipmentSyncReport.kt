package dropnext.dss.domain

import dropnext.dss.domain.fulfillment.SkipReason


/**
 * What one shipments sync did, finished or stopped: one row per shipment of the request, in request order, and one per
 * fulfillment the run decided to cancel, in the order they were sent. Both the answer to the monolith and the run's
 * summary line are derived from it, so the two cannot disagree.
 *
 * [notifiedCount] is how many of the created fulfillments had Shopify email the customer. Only the log reads it.
 */
data class ShipmentSyncReport(
  val shipments: List<ShipmentSync>,
  val cancellations: List<FulfillmentCancellation> = emptyList(),
  val notifiedCount: Int = 0,
) {
  val createdCount: Int get() = shipments.count { it.status is ShipmentSyncStatus.Created }
  val alreadyFulfilledCount: Int get() = shipments.count { it.status is ShipmentSyncStatus.AlreadyFulfilled }
  val skippedCount: Int get() = shipments.count { it.status is ShipmentSyncStatus.Skipped }
  val blockedCount: Int get() = shipments.count { it.status is ShipmentSyncStatus.Blocked }
  val refusedCount: Int get() = shipments.count { it.status is ShipmentSyncStatus.Refused }
  val canceledCount: Int get() = cancellations.count { it.status is FulfillmentCancellationStatus.Canceled }
  val cancelRefusedCount: Int get() = cancellations.count { it.status is FulfillmentCancellationStatus.Refused }
  val createdFulfillmentIds: List<ShopifyFulfillmentId>
    get() = shipments.mapNotNull { (it.status as? ShipmentSyncStatus.Created)?.fulfillmentId }
}

/** [trackingNumber] is exactly as the request spelled it: the monolith finds its shipment by that string. */
data class ShipmentSync(val trackingNumber: String, val status: ShipmentSyncStatus)

/**
 * A fulfillment id read from Shopify's snapshot is `null` only when its gid carries no numeric id, which Shopify's ids
 * always do; the ids this run created come from Shopify's answer and are never `null`.
 */
sealed interface ShipmentSyncStatus {
  data class Created(val fulfillmentId: ShopifyFulfillmentId) : ShipmentSyncStatus

  /** A live fulfillment carries the tracking number already: a re-send, not a new package. */
  data class AlreadyFulfilled(val fulfillmentId: ShopifyFulfillmentId?) : ShipmentSyncStatus

  /** The shipment does not fit the order whole, so nothing was created for it. */
  data class Skipped(val reason: SkipReason) : ShipmentSyncStatus

  /** The tracking number is on a stale fulfillment that was not canceled, so nothing was created under it. */
  data class Blocked(val fulfillmentId: ShopifyFulfillmentId?) : ShipmentSyncStatus

  /** Shopify refused the create; [messages] are its user errors, for the log only. */
  data class Refused(val messages: List<String>) : ShipmentSyncStatus

  /** The run stopped on this shipment's create. Shopify may have created it anyway: a retry finds it by its number. */
  data object Failed : ShipmentSyncStatus

  /** The run stopped before this shipment was created or classified. */
  data object NotAttempted : ShipmentSyncStatus
}

/** [trackingNumber] is the retired number whose retirement made the fulfillment stale, trimmed. */
data class FulfillmentCancellation(
  val trackingNumber: String,
  val fulfillmentId: ShopifyFulfillmentId,
  val status: FulfillmentCancellationStatus,
)

sealed interface FulfillmentCancellationStatus {
  data object Canceled : FulfillmentCancellationStatus

  /** [messages] are Shopify's user errors, for the log only. */
  data class Refused(val messages: List<String>) : FulfillmentCancellationStatus

  data object Failed : FulfillmentCancellationStatus

  data object NotAttempted : FulfillmentCancellationStatus
}
