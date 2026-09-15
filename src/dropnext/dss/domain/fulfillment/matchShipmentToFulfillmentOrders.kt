package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
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
 * claimed, so cross-shipment over-allocation is caught before any Shopify mutation.
 */
fun dryRunAllShipments(currentShopifyOrder: Order, shipments: List<Shipment>): DryRunResult {
  val lines = openFulfillmentLines(currentShopifyOrder)
  val perShipment = mutableListOf<ShipmentMatchResult.Ok>()

  shipments.forEach { shipment ->
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
      is LineMatchResult.Matched -> {
        groups.getOrPut(lineResult.fulfillmentOrderId) { mutableListOf() } += lineResult.input
        planned = planned + lineResult.input
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
  data class Matched(
    val fulfillmentOrderId: String,
    val input: FulfillmentOrderLineItemInput,
  ) : LineMatchResult

  data class Skip(val reason: SkipReason) : LineMatchResult

  data class UserError(val messages: List<String>) : LineMatchResult
}

/**
 * Files the line under the candidate with the most available quantity, first in Graphql order on a tie.
 * A line is never split over two candidates, so two half-empty lines cannot together serve it.
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

  // maxByOrNull answers the first of equals, which keeps the Graphql-order tie-break.
  val (best, available) = candidates
    .map { it to it.availableGiven(planned) }
    .filter { (_, available) -> available > 0 }
    .maxByOrNull { (_, available) -> available }
    ?: return LineMatchResult.UserError(listOf(exceedsRemaining(shipmentLineItem, remaining = 0)))

  if (shipmentLineItem.quantity > available) {
    return LineMatchResult.UserError(listOf(exceedsRemaining(shipmentLineItem, available)))
  }

  return LineMatchResult.Matched(
    fulfillmentOrderId = best.fulfillmentOrderId,
    input = FulfillmentOrderLineItemInput(id = best.line.id, quantity = shipmentLineItem.quantity),
  )
}

private fun exceedsRemaining(shipmentLineItem: ShipmentLineItem, remaining: Int): String =
  "variant ${shipmentLineItem.productVariantId} requested quantity ${shipmentLineItem.quantity} exceeds " +
    "remaining $remaining on fulfillment order"
