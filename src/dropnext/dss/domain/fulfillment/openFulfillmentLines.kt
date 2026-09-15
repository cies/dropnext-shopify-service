package dropnext.dss.domain.fulfillment

import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput


fun FulfillmentOrderStatus.isOpenForFulfillment(): Boolean = when (this) {
  FulfillmentOrderStatus.OPEN, FulfillmentOrderStatus.IN_PROGRESS -> true
  else -> false
}

/** An open fulfillment-order line with the id of the order it sits on: all the shipment plan reads from the order. */
data class OpenFulfillmentLine(
  val fulfillmentOrderId: String,
  val variantId: Long,
  val line: FulfillmentOrderLineItem,
)

/**
 * The open fulfillment-order lines of an order by variant id, each group in Graphql order. Built once per order
 * so that matching a shipment line is a lookup rather than a walk of every fulfillment order.
 */
fun openFulfillmentLines(order: Order): Map<Long, List<OpenFulfillmentLine>> =
  order.fulfillmentOrders.edges
    .map { it.node }
    .filter { it.status.isOpenForFulfillment() }
    .flatMap { fulfillmentOrder ->
      fulfillmentOrder.lineItems.edges.mapNotNull { edge ->
        // A line whose variant id cannot be read can never match a shipment line, so it is left out here rather
        // than filtered at every lookup.
        val variantId = edge.node.variant?.legacyResourceId?.toLongOrNull()
          ?: return@mapNotNull null
        OpenFulfillmentLine(fulfillmentOrder.id, variantId, edge.node)
      }
    }
    .groupBy { it.variantId }

/**
 * What is left on this line once the plan so far is honored. Sync is additive and never cancels, so this starts
 * from `remainingQuantity`, not `totalQuantity`, which still counts the units already fulfilled.
 */
fun OpenFulfillmentLine.availableGiven(planned: List<FulfillmentOrderLineItemInput>): Int =
  line.remainingQuantity - planned.filter { it.id == line.id }.sumOf { it.quantity }
