package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput


/** Why a shipment is left out of Shopify. Neither is an error: a supplier may ship more than was ordered. */
enum class SkipReason {
  /** A line's variant is not on the order: the monolith named a variant this order does not have. */
  NOT_ON_ORDER,

  /** Every variant is on the order, but at least one line asks for more than its open lines have left. */
  NO_ROOM,
}

/** A shipment line that could not be filed: what it asked for and what the open lines had left for it. */
data class ShortLine(
  val productVariantId: Long,
  val asked: Int,
  val available: Int,
)

/** How one shipment of a payload fares against the order: filed whole, already there, or left out whole. */
sealed interface ShipmentMatchResult {
  /** [groups] is keyed by the fulfillment order's gid, in the order the fulfillment orders were first matched. */
  data class Matched(val groups: Map<String, List<FulfillmentOrderLineItemInput>>) : ShipmentMatchResult

  /** The live fulfillments already carrying the shipment's tracking number. */
  data class AlreadyFulfilled(val fulfillments: List<Fulfillment>) : ShipmentMatchResult

  /** A fulfillment is a whole package, so one line that does not fit keeps every line of the shipment out. */
  data class Skipped(val reason: SkipReason, val shortLines: List<ShortLine>) : ShipmentMatchResult
}

/** Aggregates duplicate [ShipmentLineItem.productVariantId] rows within a shipment (sum quantities). */
fun normalizeShipmentLineItems(shipment: Shipment): List<ShipmentLineItem> =
  shipment.lineItems
    .groupBy { it.productVariantId }
    .map { (variantId, items) ->
      ShipmentLineItem(
        productVariantId = variantId,
        quantity = items.sumOf { it.quantity },
      )
    }

/** The variants of the order's line items, which is what "on the order" means; an unreadable variant id is left out. */
fun variantIdsOnOrder(order: Order): Set<Long> =
  order.lineItems.edges
    .mapNotNull { it.node.variant?.legacyResourceId?.toLongOrNull() }
    .toSet()

/**
 * Matches all shipments against one plan, in the order given: each shipment is matched against what the earlier ones
 * already claimed, and a skipped shipment claims nothing. A shipment whose tracking number is already on a live
 * fulfillment is not matched at all: the monolith re-sends whole payloads, and a re-sent package must neither be
 * created twice nor claim units a later shipment of the payload needs.
 *
 * A fulfillment in [canceledFulfillmentIds] was canceled by this run and counts as canceled, even if
 * [currentShopifyOrder] was loaded too soon to show it.
 */
fun dryRunAllShipments(
  currentShopifyOrder: Order,
  shipments: List<Shipment>,
  canceledFulfillmentIds: Set<String> = emptySet(),
): List<ShipmentMatchResult> {
  val lines = openFulfillmentLines(currentShopifyOrder)
  val variantsOnOrder = variantIdsOnOrder(currentShopifyOrder)
  val planned = mutableListOf<FulfillmentOrderLineItemInput>()
  return shipments.map { shipment ->
    val alreadyFulfilledBy = liveFulfillmentsWithTrackingNumber(currentShopifyOrder, shipment.trackingNumber)
      .filterNot { it.id in canceledFulfillmentIds }
    if (alreadyFulfilledBy.isNotEmpty()) return@map ShipmentMatchResult.AlreadyFulfilled(alreadyFulfilledBy)

    val result = matchShipmentToFulfillmentOrders(shipment, lines, variantsOnOrder, planned.toList())
    if (result is ShipmentMatchResult.Matched) planned += result.groups.values.flatten()
    result
  }
}

/**
 * Files every line of [shipment] on the open fulfillment-order lines, honoring [alreadyPlanned], or none of them.
 *
 * A non-positive quantity or a shipment without lines never gets here: the request validators refuse both, so either
 * is a bug and throws.
 */
fun matchShipmentToFulfillmentOrders(
  shipment: Shipment,
  lines: Map<Long, List<OpenFulfillmentLine>>,
  variantsOnOrder: Set<Long>,
  alreadyPlanned: List<FulfillmentOrderLineItemInput>,
): ShipmentMatchResult {
  val shipmentLines = normalizeShipmentLineItems(shipment)
  require(shipmentLines.isNotEmpty()) { "shipment ${shipment.trackingNumber} has no lines" }

  val groups = LinkedHashMap<String, MutableList<FulfillmentOrderLineItemInput>>()
  val shortLines = mutableListOf<ShortLine>()
  shipmentLines.forEach { shipmentLine ->
    require(shipmentLine.quantity > 0) { "variant ${shipmentLine.productVariantId} quantity must be positive" }

    val filing =
      if (shipmentLine.productVariantId !in variantsOnOrder) LineFiling.Short(available = 0)
      else fileLine(shipmentLine, lines[shipmentLine.productVariantId].orEmpty(), alreadyPlanned)
    when (filing) {
      is LineFiling.Filed -> filing.allocations.forEach { allocation ->
        groups.getOrPut(allocation.fulfillmentOrderId) { mutableListOf() } += allocation.input
      }

      is LineFiling.Short ->
        shortLines += ShortLine(shipmentLine.productVariantId, shipmentLine.quantity, filing.available)
    }
  }

  if (shortLines.isEmpty()) return ShipmentMatchResult.Matched(groups)
  val anyVariantOffTheOrder = shortLines.any { it.productVariantId !in variantsOnOrder }
  val reason = if (anyVariantOffTheOrder) SkipReason.NOT_ON_ORDER else SkipReason.NO_ROOM
  return ShipmentMatchResult.Skipped(reason, shortLines)
}

private sealed interface LineFiling {
  /** In the order the lines were taken from: the one with the most available first. */
  data class Filed(val allocations: List<LineAllocation>) : LineFiling

  data class Short(val available: Int) : LineFiling
}

/** Part of a shipment line filed under one open fulfillment-order line. */
private data class LineAllocation(
  val fulfillmentOrderId: String,
  val input: FulfillmentOrderLineItemInput,
)

/**
 * Takes the line from the candidate with the most available quantity, the first in Graphql order on a tie, and from the
 * next only what that one cannot serve. A quantity one candidate can take stays on one line; one that only several
 * can take together (a variant on two line items, or on one line routed to two locations) is spread over them.
 *
 * The lines of one shipment are distinct variants, so they never compete for a candidate and [planned] is only what
 * the earlier shipments of the payload claimed.
 */
private fun fileLine(
  shipmentLineItem: ShipmentLineItem,
  candidates: List<OpenFulfillmentLine>,
  planned: List<FulfillmentOrderLineItemInput>,
): LineFiling {
  // sortedByDescending is stable, which keeps the Graphql-order tie-break.
  val available = candidates
    .map { it to it.availableGiven(planned) }
    .filter { (_, availableOnLine) -> availableOnLine > 0 }
    .sortedByDescending { (_, availableOnLine) -> availableOnLine }
  val totalAvailable = available.sumOf { (_, availableOnLine) -> availableOnLine }
  if (shipmentLineItem.quantity > totalAvailable) return LineFiling.Short(totalAvailable)

  var left = shipmentLineItem.quantity
  val allocations = buildList {
    available.forEach { (candidate, availableOnLine) ->
      if (left == 0) return@forEach
      val taken = minOf(left, availableOnLine)
      add(LineAllocation(candidate.fulfillmentOrderId, FulfillmentOrderLineItemInput(id = candidate.line.id, quantity = taken)))
      left -= taken
    }
  }
  return LineFiling.Filed(allocations)
}
