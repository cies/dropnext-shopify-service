package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.CancelPlan
import dropnext.dss.domain.fulfillment.CancelReason
import dropnext.dss.domain.fulfillment.KeptStaleFulfillment
import dropnext.dss.domain.fulfillment.PackageContent
import dropnext.dss.domain.fulfillment.PlannedCancel
import dropnext.dss.domain.fulfillment.fulfillmentsToCompare
import dropnext.dss.domain.fulfillment.planCancels
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.dss.lib.shopify.orderGid
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/**
 * The order as the run first loaded it, what [planCancels] decided against it, and what the decision was made from, so
 * the fit check can be run again once Shopify has refused a cancel.
 *
 * The cancels are sent in two waves: [retiredCancels] first, because what they give back is room the others' fit check
 * counts on, then [contentChangedCancels] of the plan that knows which of the first wave Shopify refused.
 * A fulfillment whose gid carries no numeric id cannot be named in a mutation; it is in [unsendableCancels] instead.
 * [goneFulfillments] are the ones Shopify no longer has, which cannot be canceled either.
 * Both stay in the way of their number, like a cancel Shopify refuses.
 */
data class CancelDecision(
  val order: Order,
  val plan: CancelPlan,
  val goneFulfillments: List<Fulfillment>,
  val shipments: List<Shipment>,
  val retiredTrackingNumbers: List<String>,
  val contents: Map<String, PackageContent>,
) {
  val goneFulfillmentIds: Set<String> = goneFulfillments.map { it.id }.toSet()

  val retiredCancels: List<ShopifyMutation.FulfillmentCancel> = mutationsOf(CancelReason.RETIRED)

  val contentChangedCancels: List<ShopifyMutation.FulfillmentCancel> = mutationsOf(CancelReason.CONTENT_CHANGED)

  /** Which fulfillment each planned cancel is for, since the mutation carries only its id. */
  val fulfillmentByCancel: Map<ShopifyMutation.FulfillmentCancel, Fulfillment> =
    plan.cancels.mapNotNull { cancel -> cancel.mutationOrNull()?.let { it to cancel.fulfillment } }.toMap()

  val unsendableCancels: List<Fulfillment> =
    plan.cancels.filter { it.mutationOrNull() == null }.map { it.fulfillment }

  private fun mutationsOf(reason: CancelReason) =
    plan.cancels.filter { it.reason == reason }.mapNotNull { it.mutationOrNull() }
}

private fun PlannedCancel.mutationOrNull(): ShopifyMutation.FulfillmentCancel? =
  legacyIdFromGid(fulfillment.id)?.let { ShopifyMutation.FulfillmentCancel(ShopifyFulfillmentId(it), trackingNumber) }

/**
 * The decision again, with the cancels Shopify refused in the first wave left out: they stay live and give back no
 * room, so a content-changed cancel whose shipment counted on that room is dropped rather than sent. Pure but for the
 * line it logs per cancel it had to drop.
 */
fun refitCancels(
  shopifyOrderId: ShopifyOrderId,
  decision: CancelDecision,
  refusedFulfillmentIds: Set<String>,
): CancelDecision {
  if (refusedFulfillmentIds.isEmpty()) return decision
  val refitted = decision.copy(
    plan = planCancels(
      order = decision.order,
      shipments = decision.shipments,
      retiredTrackingNumbers = decision.retiredTrackingNumbers,
      contents = decision.contents,
      uncancelableFulfillmentIds = decision.goneFulfillmentIds + refusedFulfillmentIds,
    ),
  )
  val keptBefore = decision.plan.keptStale.map { it.fulfillment.id }.toSet()
  refitted.plan.keptStale
    .filterNot { it.fulfillment.id in keptBefore }
    .forEach { logKeptStale(shopifyOrderId, it) }
  return refitted
}

/**
 * Read-only: loads the order and the content of the fulfillments [planCancels] has to compare, then plans the cancels
 * and logs what it decided. A fulfillment whose content cannot be read whole is compared as unknown, and one Shopify no
 * longer has counts as canceled: neither stops the run.
 */
suspend fun determineFulfillmentCancels(
  shopifyGqlService: ShopifyGraphqlService,
  shopifyOrderId: ShopifyOrderId,
  shipments: List<Shipment>,
  retiredTrackingNumbers: List<String>,
  budget: ShipmentSyncBudget,
): ShopifyResult<CancelDecision> {
  if (!budget.allowsAnotherCall()) return Failure(budget.timedOut())
  val order = when (val loaded = shopifyGqlService.orderForDss(orderGid(shopifyOrderId))) {
    is Failure -> return loaded
    is Success -> loaded.value
  }

  val contents = mutableMapOf<String, PackageContent>()
  val gone = mutableListOf<Fulfillment>()
  fulfillmentsToCompare(order, shipments, retiredTrackingNumbers).forEach { fulfillment ->
    if (!budget.allowsAnotherCall()) return Failure(budget.timedOut())
    val fulfillmentId = legacyIdFromGid(fulfillment.id)
    when (val loaded = shopifyGqlService.fulfillmentContent(fulfillment.id)) {
      is Success -> contents[fulfillment.id] = loaded.value
      is Failure -> when (val cause = loaded.reason) {
        is ShopifyError.Truncated -> {
          log.warn {
            "sync-shipments content unknown orderId=$shopifyOrderId fulfillmentId=$fulfillmentId error=${cause.message}"
          }
          contents[fulfillment.id] = PackageContent.Unknown
        }
        // Shopify keeps a canceled fulfillment, so an id the order listed a moment ago and Shopify does not have is
        // an anomaly. The order snapshot is the better evidence: the fulfillment is left alone, and whoever has its
        // number is reported blocked rather than given a second fulfillment for it.
        is ShopifyError.NotFound -> {
          log.warn { "sync-shipments fulfillment gone orderId=$shopifyOrderId fulfillmentId=$fulfillmentId" }
          gone += fulfillment
        }
        else -> return loaded
      }
    }
  }

  val plan = planCancels(order, shipments, retiredTrackingNumbers, contents, gone.map { it.id }.toSet())
  logDecisions(shopifyOrderId, plan)
  return Success(CancelDecision(order, plan, gone, shipments, retiredTrackingNumbers, contents))
}

/** Kept rather than canceled, so the package keeps its tracking events; each run checks again. */
private fun logKeptStale(shopifyOrderId: ShopifyOrderId, kept: KeptStaleFulfillment) {
  val shortLines = kept.shortLines.joinToString(", ", "[", "]") {
    "variant=${it.productVariantId}:asked=${it.asked}:available=${it.available}"
  }
  log.warn {
    "sync-shipments kept stale fulfillment orderId=$shopifyOrderId tracking=${kept.trackingNumber} " +
      "fulfillmentId=${legacyIdFromGid(kept.fulfillment.id)} reason=replacement_does_not_fit short=$shortLines"
  }
}

private fun logDecisions(shopifyOrderId: ShopifyOrderId, plan: CancelPlan) {
  plan.cancels.forEach { cancel ->
    log.info {
      "sync-shipments cancel orderId=$shopifyOrderId tracking=${cancel.trackingNumber} " +
        "fulfillmentId=${legacyIdFromGid(cancel.fulfillment.id)} reason=${cancel.reason.name.lowercase()}"
    }
  }
  plan.keptStale.forEach { logKeptStale(shopifyOrderId, it) }
  // Only a fulfillment made by hand carries two tracking numbers; a human decides what it should hold.
  plan.keptShared.forEach { kept ->
    log.warn {
      "sync-shipments kept stale fulfillment orderId=$shopifyOrderId tracking=${kept.retiredTrackingNumber} " +
        "fulfillmentId=${legacyIdFromGid(kept.fulfillment.id)} reason=${kept.reason.name.lowercase()} " +
        "liveTracking=${kept.liveTrackingNumbers}"
    }
  }
}
