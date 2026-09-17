package dropnext.dss.testutil.fixture

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderEdge
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant


/** Builds an order whose open fulfillment orders mirror the diagram cross-FO scenario (LI1 + LI5). */
internal fun diagramCrossFoOrder(): Order {
  val fo1 =
    openFulfillmentOrder(
      foId = 301L,
      lineItemId = 401L,
      variantId = 1L,
      remaining = 5,
    )
  val fo2 =
    openFulfillmentOrder(
      foId = 302L,
      lineItemId = 402L,
      variantId = 5L,
      remaining = 5,
    )
  return minimalOrder().copy(
    fulfillmentOrders =
      FulfillmentOrderConnection(
        pageInfo = COMPLETE_PAGE,
        edges = listOf(FulfillmentOrderEdge(node = fo1), FulfillmentOrderEdge(node = fo2)),
      ),
  )
}

internal fun diagramCrossFoShipment(): Shipment =
  Shipment(
    trackingNumber = "TRK-A",
    carrier = "UPS",
    trackingUrl = null,
    lineItems = listOf(
      ShipmentLineItem(productVariantId = 1L, quantity = 1),
      ShipmentLineItem(productVariantId = 5L, quantity = 1),
    ),
  )

internal fun openFulfillmentOrder(
  variantId: Long,
  remaining: Int,
  foId: Long = 301L,
  lineItemId: Long = 401L,
  status: FulfillmentOrderStatus = FulfillmentOrderStatus.OPEN,
): FulfillmentOrder =
  FulfillmentOrder(
    id = "gid://shopify/FulfillmentOrder/$foId",
    status = status,
    lineItems = foLineItemConnection(variantId, remaining, lineItemId),
  )

internal fun foLineItemConnection(
  variantId: Long,
  remaining: Int,
  lineItemId: Long = 401L,
): FulfillmentOrderLineItemConnection =
  FulfillmentOrderLineItemConnection(
    pageInfo = COMPLETE_PAGE,
    edges =
      listOf(
        FulfillmentOrderLineItemEdge(
          node =
            FulfillmentOrderLineItem(
              id = "gid://shopify/FulfillmentOrderLineItem/$lineItemId",
              remainingQuantity = remaining,
              variant = ProductVariant(legacyResourceId = variantId.toString()),
            ),
        ),
      ),
  )

internal fun orderWithFulfillmentOrders(vararg fos: FulfillmentOrder): Order =
  minimalOrder().copy(
    fulfillmentOrders =
      FulfillmentOrderConnection(
        pageInfo = COMPLETE_PAGE,
        edges = fos.map { FulfillmentOrderEdge(node = it) },
      ),
  )
