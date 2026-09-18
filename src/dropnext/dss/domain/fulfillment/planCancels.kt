package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order


/** Why a live fulfillment under a retired tracking number is canceled. */
enum class CancelReason {
  /** No live shipment has the number any more. */
  RETIRED,

  /** A live shipment has the number, with another content: the fulfillment holds the package as it was. */
  CONTENT_CHANGED,
}

/** [trackingNumber] is the retired number the cancel is for, trimmed. */
data class PlannedCancel(
  val fulfillment: Fulfillment,
  val trackingNumber: String,
  val reason: CancelReason,
)

/**
 * A fulfillment kept although its content is outdated: the live shipment with its number would not fit after the
 * run's cancels, and a package with an outdated content in Shopify is better than one gone from it.
 */
data class KeptStaleFulfillment(
  val fulfillment: Fulfillment,
  val trackingNumber: String,
  val shortLines: List<ShortLine>,
)

/** Why a fulfillment under a retired number is kept although its content matches no shipment. */
enum class KeptSharedReason {
  /** It also carries the number of a live shipment that was never retired, whose package it may well hold. */
  CARRIES_LIVE_TRACKING_NUMBER,

  /** Two live shipments took over a number it carries: which of them it should hold is not for this service to guess. */
  CARRIES_TWO_REUSED_TRACKING_NUMBERS,
}

/**
 * A fulfillment kept although it carries a retired number, because canceling it would take a package away that
 * something else may need. Only a fulfillment made by hand carries two tracking numbers, so a human decides what
 * should become of it; the shipments under its numbers are reported as fulfilled by it.
 */
data class KeptSharedFulfillment(
  val fulfillment: Fulfillment,
  val retiredTrackingNumber: String,
  val liveTrackingNumbers: List<String>,
  val reason: KeptSharedReason,
)

/**
 * What [planCancels] decided for the live fulfillments under retired numbers.
 * A fulfillment kept because it matches its live shipment is not listed.
 */
data class CancelPlan(
  val cancels: List<PlannedCancel>,
  val keptStale: List<KeptStaleFulfillment>,
  val keptShared: List<KeptSharedFulfillment>,
)

/**
 * The live fulfillments whose content [planCancels] needs: those that carry a retired number a live shipment has too.
 * When there is one of those, also those that carry a retired number no live shipment has, because their cancel gives
 * back room a replacing shipment may need. A run without a reused number loads no content at all.
 */
fun fulfillmentsToCompare(
  order: Order,
  shipments: List<Shipment>,
  retiredTrackingNumbers: List<String>,
): List<Fulfillment> {
  val liveNumbers = shipments.map { it.trackingNumber.trim() }.toSet()
  val (reused, gone) = retiredTrackingNumbers.map { it.trim() }.distinct().partition { it in liveNumbers }
  val compared = reused.flatMap { liveFulfillmentsWithTrackingNumber(order, it) }
  if (compared.isEmpty()) return emptyList()
  return (compared + gone.flatMap { liveFulfillmentsWithTrackingNumber(order, it) }).distinctBy { it.id }
}

/**
 * Decides which live fulfillments under [retiredTrackingNumbers] to cancel, against the order as first loaded.
 *
 * A live fulfillment that carries a retired number is canceled, unless a live shipment with one of its numbers has
 * exactly its content, or two live shipments took one of its numbers over.
 * [contents] is keyed by fulfillment gid; a fulfillment missing from it has unknown content, which matches nothing and
 * gives back no room, so it is replaced only when its shipment fits the room the order already has.
 * A fulfillment in [uncancelableFulfillmentIds] is one this run cannot cancel — Shopify no longer has it, or refused
 * its cancel — so it is neither planned nor counted as room, and it stays in the way of its number.
 *
 * A content-changed cancel is kept only if its live shipment fits whole into the room of [order] plus what the planned
 * cancels give back.
 * Without that, a cancel whose replacement cannot fit takes a working fulfillment away for good.
 * The demand is counted by name rather than by asking [order] which shipments are fulfilled, because [order] still
 * shows the fulfillments about to be canceled as live.
 */
fun planCancels(
  order: Order,
  shipments: List<Shipment>,
  retiredTrackingNumbers: List<String>,
  contents: Map<String, PackageContent>,
  uncancelableFulfillmentIds: Set<String> = emptySet(),
): CancelPlan {
  val shipmentsByNumber = shipments.associateBy { it.trackingNumber.trim() }
  val retiredNumbers = retiredTrackingNumbers.map { it.trim() }.distinct()
  val unretiredLiveNumbers = shipmentsByNumber.keys - retiredNumbers.toSet()
  val fulfillments = retiredNumbers
    .flatMap { liveFulfillmentsWithTrackingNumber(order, it) }
    .distinctBy { it.id }
    .filterNot { it.id in uncancelableFulfillmentIds }

  val cancels = mutableListOf<PlannedCancel>()
  val keptShared = mutableListOf<KeptSharedFulfillment>()
  fulfillments.forEach { fulfillment ->
    val carried = fulfillment.trackingNumbers()
    val retiredCarried = retiredNumbers.filter { it in carried }
    val sharedWith = carried.filter { it in unretiredLiveNumbers }.distinct()
    if (sharedWith.isNotEmpty()) {
      keptShared += KeptSharedFulfillment(
        fulfillment = fulfillment,
        retiredTrackingNumber = retiredCarried.first(),
        liveTrackingNumbers = sharedWith,
        reason = KeptSharedReason.CARRIES_LIVE_TRACKING_NUMBER,
      )
      return@forEach
    }

    val content = contents[fulfillment.id] ?: PackageContent.Unknown
    val reusedCarried = retiredCarried.filter { it in shipmentsByNumber }
    val matches = reusedCarried.any { content.isSameAs(packageContentOf(shipmentsByNumber.getValue(it))) }
    when {
      reusedCarried.size > 1 -> keptShared += KeptSharedFulfillment(
        fulfillment = fulfillment,
        retiredTrackingNumber = retiredCarried.first(),
        liveTrackingNumbers = reusedCarried,
        reason = KeptSharedReason.CARRIES_TWO_REUSED_TRACKING_NUMBERS,
      )
      reusedCarried.isEmpty() -> cancels += PlannedCancel(fulfillment, retiredCarried.first(), CancelReason.RETIRED)
      matches -> Unit
      else -> cancels += PlannedCancel(fulfillment, reusedCarried.first(), CancelReason.CONTENT_CHANGED)
    }
  }

  // A fulfillment this run cannot cancel is left out on purpose: it stays live and still holds its number, so its
  // shipment is blocked rather than waiting for room.
  val fitting = keepCancelsWhoseShipmentFits(order, shipments, cancels, contents)
  return CancelPlan(fitting.cancels, fitting.keptStale, keptShared)
}

private data class FitCheck(val cancels: List<PlannedCancel>, val keptStale: List<KeptStaleFulfillment>)

/**
 * Drops the content-changed cancel of a shipment that would not fit after the cancels, one at a time, since dropping
 * one shrinks the room the others count on and takes its own shipment out of the demand.
 *
 * The demand is every live shipment that Shopify has carried under its number and has no fulfillment left to hold it:
 * the replacing shipments of this run, and the ones whose fulfillment was canceled before it (by an earlier run of this
 * design, or by the merchant). Those are the shipments the create plan plans first, so counting them here is what keeps
 * a cancel from freeing room that another shipment will take. A shipment Shopify has never carried is not counted: it
 * has waited for room before and can wait again.
 *
 * Counting per variant is enough: the matcher may spread a line over every open line of its variant, on any
 * fulfillment order.
 */
private fun keepCancelsWhoseShipmentFits(
  order: Order,
  shipments: List<Shipment>,
  plannedCancels: List<PlannedCancel>,
  contents: Map<String, PackageContent>,
): FitCheck {
  val openRoom = openFulfillmentLines(order).mapValues { (_, lines) -> lines.sumOf { it.line.remainingQuantity } }
  val variantsOnOrder = variantIdsOnOrder(order)
  var cancels = plannedCancels
  val keptStale = mutableListOf<KeptStaleFulfillment>()
  while (true) {
    val room = openRoom.toMutableMap()
    cancels.forEach { cancel ->
      val content = contents[cancel.fulfillment.id] as? PackageContent.Known ?: return@forEach
      content.quantityByVariantId.forEach { (variantId, quantity) -> room.merge(variantId, quantity, Int::plus) }
    }

    val goingIds = cancels.map { it.fulfillment.id }.toSet()
    val dropped = shipments
      .filter { shipmentNeedsRoom(order, it, goingIds) }
      .firstNotNullOfOrNull { shipment -> claimRoomOrDrop(shipment, room, variantsOnOrder, cancels) }
      ?: return FitCheck(cancels, keptStale)

    keptStale += dropped.cancels.map { KeptStaleFulfillment(it.fulfillment, it.trackingNumber, dropped.shortLines) }
    cancels = cancels - dropped.cancels.toSet()
  }
}

/** The content-changed cancels to drop because [shortLines] of their shipment have nowhere to go. */
private data class DroppedCancels(val cancels: List<PlannedCancel>, val shortLines: List<ShortLine>)

/**
 * Takes [shipment]'s content out of [room] when it fits, and answers the cancels to drop when it does not. A shipment
 * that does not fit and has no cancel of its own claims nothing and holds nothing up: it is skipped by the create plan.
 */
private fun claimRoomOrDrop(
  shipment: Shipment,
  room: MutableMap<Long, Int>,
  variantsOnOrder: Set<Long>,
  cancels: List<PlannedCancel>,
): DroppedCancels? {
  val content = packageContentOf(shipment).quantityByVariantId
  val shortLines = content.mapNotNull { (variantId, quantity) ->
    val available = if (variantId in variantsOnOrder) room[variantId] ?: 0 else 0
    if (quantity > available) ShortLine(variantId, quantity, available) else null
  }
  if (shortLines.isEmpty()) {
    content.forEach { (variantId, quantity) -> room.merge(variantId, -quantity, Int::plus) }
    return null
  }
  val number = shipment.trackingNumber.trim()
  val ownCancels = cancels.filter { it.reason == CancelReason.CONTENT_CHANGED && it.trackingNumber == number }
  return ownCancels.takeIf { it.isNotEmpty() }?.let { DroppedCancels(it, shortLines) }
}

/**
 * Whether Shopify has carried [shipment]'s number and has no fulfillment left that holds it: every live fulfillment
 * under the number is being canceled by this run.
 */
private fun shipmentNeedsRoom(order: Order, shipment: Shipment, goingFulfillmentIds: Set<String>): Boolean {
  val number = shipment.trackingNumber
  if (fulfillmentsWithTrackingNumber(order, number).isEmpty()) return false
  return liveFulfillmentsWithTrackingNumber(order, number).all { it.id in goingFulfillmentIds }
}
