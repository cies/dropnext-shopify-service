package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.FulfillmentCancellationStatus
import dropnext.dss.domain.ShipmentSyncReport
import dropnext.dss.domain.ShipmentSyncStatus
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.errorLabel
import dropnext.graphql.generated.getorderfordss.Fulfillment
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/** A run that Shopify stopped part-way: [report] says what landed before [cause], and what was never tried. */
data class StoppedShipmentSync(val report: ShipmentSyncReport, val cause: ShopifyError)

/**
 * Reconciles the order's fulfillments with the whole state of its shipments: every live shipment, and the tracking
 * numbers of the retired ones.
 *
 * The run loads the order and decides which live fulfillments under a retired number to cancel
 * ([determineFulfillmentCancels]).
 * It sends those cancels in two waves: first the fulfillments of numbers no live shipment has any more, then, knowing
 * which of those Shopify refused, the ones a live shipment has taken over with another content.
 * It loads the order again when a cancel went through, and creates a fulfillment for each live shipment whose number is
 * on no live fulfillment and that fits whole ([determineShopifyMutations]).
 * Fulfillment orders are resolved by matching `product_variant_id` against the open fulfillment-order lines.
 * A shipment that does not fit is skipped, since a supplier may ship more than was ordered, and a shipment whose number
 * is on a stale fulfillment the run did not cancel is blocked.
 *
 * Answers the report on success and on failure alike: a run that stopped still canceled and created what it did, and
 * the caller is told which.
 * A write Shopify refuses is an outcome, not a stop.
 * The run starts no Shopify call that [budget] would not hold, and stops as [ShopifyError.TimedOut] instead.
 */
suspend fun syncShopifyShipmentsToFulfillments(
  shopifyGqlService: ShopifyGraphqlService,
  payload: SyncShipmentsWithFulfillmentsRequest,
  budget: ShipmentSyncBudget = ShipmentSyncBudget.startingNow(),
): Result<ShipmentSyncReport, StoppedShipmentSync> {
  val shopifyOrderId = ShopifyOrderId(payload.shopifyOrderId)
  val shipments = payload.shipments
  val retiredTrackingNumbers = payload.retiredTrackingNumbers.orEmpty()

  val decision =
    determineFulfillmentCancels(shopifyGqlService, shopifyOrderId, shipments, retiredTrackingNumbers, budget)
  val decided = when (decision) {
    is Failure -> return stopped(shopifyOrderId, notAttemptedShipmentSyncReport(shipments), decision.reason)
    is Success -> decision.value
  }

  val retiredCancels = decided.retiredCancels
  val retiredEffects = effectShopifyMutations(shopifyGqlService, retiredCancels, budget)
  retiredEffects.stop?.let {
    val report = notAttemptedShipmentSyncReport(shipments, retiredCancels, retiredEffects)
    return stopped(shopifyOrderId, report, it.cause)
  }

  val refitted = refitCancels(shopifyOrderId, decided, refusedFulfillmentIds(decided, retiredEffects))
  val contentChangedCancels = refitted.contentChangedCancels
  val contentChangedEffects = effectShopifyMutations(shopifyGqlService, contentChangedCancels, budget)

  val cancels = retiredCancels + contentChangedCancels
  val canceled =
    MutationEffectReport(retiredEffects.effects + contentChangedEffects.effects, contentChangedEffects.stop)
  val fulfillmentByCancel = decided.fulfillmentByCancel + refitted.fulfillmentByCancel
  val cancelsOnly = notAttemptedShipmentSyncReport(shipments, cancels, canceled)
  canceled.stop?.let { return stopped(shopifyOrderId, cancelsOnly, it.cause) }

  val aftermath = aftermathOf(refitted, fulfillmentByCancel, canceled)
  val determined = determineShopifyMutations(shopifyGqlService, shopifyOrderId, shipments, aftermath, budget)
  val plan = when (determined) {
    is Failure -> return stopped(shopifyOrderId, cancelsOnly, determined.reason)
    is Success -> determined.value
  }

  val created = effectShopifyMutations(shopifyGqlService, plan.mutations, budget)
  val report = buildShipmentSyncReport(
    shipments = shipments,
    plan = plan,
    cancels = cancels,
    effected = MutationEffectReport(canceled.effects + created.effects, created.stop),
  )
  val stop = created.stop ?: return Success(report.also { logRun(shopifyOrderId, it, stoppedBy = null) })
  return stopped(shopifyOrderId, report, stop.cause)
}

private fun stopped(
  shopifyOrderId: ShopifyOrderId,
  report: ShipmentSyncReport,
  cause: ShopifyError,
): Failure<StoppedShipmentSync> {
  logRun(shopifyOrderId, report, stoppedBy = cause)
  return Failure(StoppedShipmentSync(report, cause))
}

/** The fulfillments Shopify refused to cancel, which stay live and give back no room. */
private fun refusedFulfillmentIds(decision: CancelDecision, effected: MutationEffectReport): Set<String> =
  effected.effects
    .filterIsInstance<MutationEffect.Refused>()
    .mapNotNull { decision.fulfillmentByCancel[it.mutation]?.id }
    .toSet()

/**
 * A fulfillment the run canceled counts as canceled whatever the reload shows, so a slow reload cannot make its
 * shipment look fulfilled. Every fulfillment the run could not cancel — one Shopify refused, one Shopify no longer
 * has, one whose gid carries no numeric id — is still live and stays in the way of its number, like one kept because
 * its shipment would not fit.
 */
private fun aftermathOf(
  decision: CancelDecision,
  fulfillmentByCancel: Map<ShopifyMutation.FulfillmentCancel, Fulfillment>,
  canceled: MutationEffectReport,
): CancelAftermath {
  val canceledIds = canceled.effects
    .filterIsInstance<MutationEffect.Canceled>()
    .mapNotNull { fulfillmentByCancel[it.mutation]?.id }
  val refused = canceled.effects
    .filterIsInstance<MutationEffect.Refused>()
    .mapNotNull { fulfillmentByCancel[it.mutation] }
  val kept = decision.plan.keptStale.map { it.fulfillment }
  return CancelAftermath(
    orderBeforeCancels = decision.order,
    canceledFulfillmentIds = canceledIds.toSet(),
    blockingFulfillments = kept + refused + decision.unsendableCancels + decision.goneFulfillments,
    orderChanged = canceledIds.isNotEmpty(),
  )
}

private fun logRun(shopifyOrderId: ShopifyOrderId, report: ShipmentSyncReport, stoppedBy: ShopifyError?) {
  logRefusals(shopifyOrderId, report)
  logSummary(shopifyOrderId, report, stoppedBy)
}

/**
 * One summary line per run, so a run can be found by order and shop and read off in Logflare. A stopped run is a
 * `warn`: the monolith retries it, and a run that keeps stopping shows up as a series of these.
 */
private fun logSummary(shopifyOrderId: ShopifyOrderId, report: ShipmentSyncReport, stoppedBy: ShopifyError?) {
  val line = "sync-shipments orderId=$shopifyOrderId " +
    "created=${report.createdCount} alreadyFulfilled=${report.alreadyFulfilledCount} " +
    "skipped=${report.skippedCount} blocked=${report.blockedCount} refused=${report.refusedCount} " +
    "canceled=${report.canceledCount} cancelRefused=${report.cancelRefusedCount} " +
    "notified=${report.notifiedCount} stoppedBy=${stoppedBy?.errorLabel ?: "none"} " +
    "fulfillmentIds=${report.createdFulfillmentIds.map { it.value }}"
  if (stoppedBy == null) log.info { line } else log.warn { line }
}

/**
 * A refusal is the one outcome no retry fixes: Shopify will refuse the same write on every run until someone looks at
 * the shipment or the order, so each one gets a line of its own with Shopify's reasons.
 */
private fun logRefusals(shopifyOrderId: ShopifyOrderId, report: ShipmentSyncReport) {
  report.shipments.forEach { shipment ->
    val refused = shipment.status as? ShipmentSyncStatus.Refused ?: return@forEach
    log.error {
      "sync-shipments create refused orderId=$shopifyOrderId tracking=${shipment.trackingNumber} " +
        "error=${refused.messages.joinToString("; ")}"
    }
  }
  report.cancellations.forEach { cancellation ->
    val refused = cancellation.status as? FulfillmentCancellationStatus.Refused ?: return@forEach
    log.error {
      "sync-shipments cancel refused orderId=$shopifyOrderId tracking=${cancellation.trackingNumber} " +
        "fulfillmentId=${cancellation.fulfillmentId} error=${refused.messages.joinToString("; ")}"
    }
  }
}
