package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput

enum class SkipReason {
  NO_OPEN_FO,
  ZERO_REMAINING,
}

data class SkippedShipmentLine(
  val productVariantId: Long,
  val quantity: Int,
  val reason: SkipReason,
  val trackingNumber: String,
)

/** Result of matching a monolith [Shipment] to open Shopify fulfillment order line items. */
sealed interface ShipmentMatchResult {
  /** [groups] is keyed by the fulfillment order's gid, in the order the fulfillment orders were first matched. */
  data class Ok(
    val groups: Map<String, List<FulfillmentOrderLineItemInput>>,
    val skipped: List<SkippedShipmentLine>,
    /** The live fulfillments already carrying the shipment's tracking number; when there are any, nothing was matched. */
    val alreadyFulfilledBy: List<Fulfillment> = emptyList(),
  ) : ShipmentMatchResult

  data class UserError(val messages: List<String>) : ShipmentMatchResult
}

sealed interface DryRunResult {
  data class Ok(val perShipment: List<ShipmentMatchResult.Ok>) : DryRunResult

  data class UserError(val messages: List<String>) : DryRunResult
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

/**
 * Matches all shipments against one plan: each shipment is matched against what the earlier ones already
 * claimed, so cross-shipment over-allocation is caught before any Shopify mutation. A shipment whose tracking number
 * is already on a live fulfillment is not matched at all: the monolith re-sends whole payloads, and a re-sent
 * package must neither be created twice nor claim units a later shipment of the payload needs.
 */
fun dryRunAllShipments(currentShopifyOrder: Order, shipments: List<Shipment>): DryRunResult {
  val lines = openFulfillmentLines(currentShopifyOrder)
  val perShipment = mutableListOf<ShipmentMatchResult.Ok>()

  shipments.forEach { shipment ->
    val alreadyFulfilledBy = liveFulfillmentsWithTrackingNumber(currentShopifyOrder, shipment.trackingNumber)
    if (alreadyFulfilledBy.isNotEmpty()) {
      perShipment += ShipmentMatchResult.Ok(groups = emptyMap(), skipped = emptyList(), alreadyFulfilledBy = alreadyFulfilledBy)
      return@forEach
    }
    val planned = perShipment.flatMap { it.groups.values.flatten() }
    when (val result = matchShipmentToFulfillmentOrders(shipment, lines, planned)) {
      is ShipmentMatchResult.Ok -> perShipment += result
      is ShipmentMatchResult.UserError -> return DryRunResult.UserError(result.messages)
    }
  }
  return DryRunResult.Ok(perShipment)
}

/**
 * Maps shipment line items to open fulfillment orders. Unmatched variants are skipped (partial match).
 *
 * Returns [ShipmentMatchResult.UserError] when the requested quantity exceeds what is available once
 * [alreadyPlanned] is honored.
 */
fun matchShipmentToFulfillmentOrders(
  shipment: Shipment,
  lines: Map<Long, List<OpenFulfillmentLine>>,
  alreadyPlanned: List<FulfillmentOrderLineItemInput>,
): ShipmentMatchResult {
  val groups = LinkedHashMap<String, MutableList<FulfillmentOrderLineItemInput>>()
  val skipped = mutableListOf<SkippedShipmentLine>()
  var planned = alreadyPlanned

  normalizeShipmentLineItems(shipment).forEach { shipmentLineItem ->
    if (shipmentLineItem.quantity <= 0) {
      return ShipmentMatchResult.UserError(
        listOf("variant ${shipmentLineItem.productVariantId} quantity must be positive"),
      )
    }

    val candidates = lines[shipmentLineItem.productVariantId].orEmpty()
    when (val lineResult = matchShipmentLineItem(shipmentLineItem, candidates, planned)) {
      is LineMatchResult.Matched -> lineResult.allocations.forEach { allocation ->
        groups.getOrPut(allocation.fulfillmentOrderId) { mutableListOf() } += allocation.input
        planned = planned + allocation.input
      }

      is LineMatchResult.Skip ->
        skipped += SkippedShipmentLine(
          productVariantId = shipmentLineItem.productVariantId,
          quantity = shipmentLineItem.quantity,
          reason = lineResult.reason,
          trackingNumber = shipment.trackingNumber,
        )

      is LineMatchResult.UserError ->
        return ShipmentMatchResult.UserError(lineResult.messages)
    }
  }

  return ShipmentMatchResult.Ok(groups, skipped)
}

private sealed interface LineMatchResult {
  /** In the order the lines were taken from: the one with the most available first. */
  data class Matched(val allocations: List<LineAllocation>) : LineMatchResult

  data class Skip(val reason: SkipReason) : LineMatchResult

  data class UserError(val messages: List<String>) : LineMatchResult
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
 */
private fun matchShipmentLineItem(
  shipmentLineItem: ShipmentLineItem,
  candidates: List<OpenFulfillmentLine>,
  planned: List<FulfillmentOrderLineItemInput>,
): LineMatchResult {
  if (candidates.isEmpty()) return LineMatchResult.Skip(SkipReason.NO_OPEN_FO)

  // The skip reads Shopify's snapshot and the errors below read the plan: a line Shopify already fulfilled is
  // a skip, a unit an earlier shipment of this same payload claimed is a payload problem to refuse.
  if (candidates.all { it.line.remainingQuantity <= 0 }) return LineMatchResult.Skip(SkipReason.ZERO_REMAINING)

  // sortedByDescending is stable, which keeps the Graphql-order tie-break.
  val available = candidates
    .map { it to it.availableGiven(planned) }
    .filter { (_, availableOnLine) -> availableOnLine > 0 }
    .sortedByDescending { (_, availableOnLine) -> availableOnLine }
  val totalAvailable = available.sumOf { (_, availableOnLine) -> availableOnLine }
  if (shipmentLineItem.quantity > totalAvailable) {
    return LineMatchResult.UserError(listOf(exceedsRemaining(shipmentLineItem, totalAvailable)))
  }

  var left = shipmentLineItem.quantity
  val allocations = buildList {
    available.forEach { (candidate, availableOnLine) ->
      if (left == 0) return@forEach
      val taken = minOf(left, availableOnLine)
      add(LineAllocation(candidate.fulfillmentOrderId, FulfillmentOrderLineItemInput(id = candidate.line.id, quantity = taken)))
      left -= taken
    }
  }
  return LineMatchResult.Matched(allocations)
}

private fun exceedsRemaining(shipmentLineItem: ShipmentLineItem, remaining: Int): String =
  "variant ${shipmentLineItem.productVariantId} requested quantity ${shipmentLineItem.quantity} exceeds " +
    "remaining $remaining on the order's open fulfillment orders"
