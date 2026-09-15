package dropnext.dss.testutil.fixture

import dropnext.graphql.generated.enums.CurrencyCode
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderEdge
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.FulfillmentTrackingInfo
import dropnext.graphql.generated.getorderfordss.LineItem
import dropnext.graphql.generated.getorderfordss.LineItemConnection
import dropnext.graphql.generated.getorderfordss.LineItemEdge
import dropnext.graphql.generated.getorderfordss.MoneyBag
import dropnext.graphql.generated.getorderfordss.MoneyBag2
import dropnext.graphql.generated.getorderfordss.MoneyV2
import dropnext.graphql.generated.getorderfordss.MoneyV22
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant

internal fun minimalOrder(
  fulfillment: OrderDisplayFulfillmentStatus = OrderDisplayFulfillmentStatus.UNFULFILLED,
  financial: OrderDisplayFinancialStatus = OrderDisplayFinancialStatus.PAID,
): Order {
  val variant =
    ProductVariant(
      id = "gid://shopify/ProductVariant/101",
      legacyResourceId = "101",
    )
  val lineItem =
    LineItem(
      id = "gid://shopify/LineItem/201",
      quantity = 2,
      name = "T-Shirt - Blue",
      title = "T-Shirt",
      originalUnitPriceSet = MoneyBag2(shopMoney = MoneyV22(amount = "19.99")),
      variant = variant,
    )
  return Order(
    id = "gid://shopify/Order/1001",
    name = "#1001",
    legacyResourceId = "1001",
    createdAt = "2026-04-25T10:30:00+00:00",
    totalPriceSet =
      MoneyBag(
        shopMoney = MoneyV2(amount = "39.98", currencyCode = CurrencyCode.USD),
      ),
    displayFinancialStatus = financial,
    displayFulfillmentStatus = fulfillment,
    shippingAddress = null,
    lineItems = LineItemConnection(edges = listOf(LineItemEdge(node = lineItem))),
    fulfillmentOrders =
      FulfillmentOrderConnection(
        edges =
          listOf(
            FulfillmentOrderEdge(
              node =
                FulfillmentOrder(
                  id = "gid://shopify/FulfillmentOrder/301",
                  status = FulfillmentOrderStatus.OPEN,
                  lineItems =
                    FulfillmentOrderLineItemConnection(
                      edges =
                        listOf(
                          FulfillmentOrderLineItemEdge(
                            node =
                              FulfillmentOrderLineItem(
                                id = "gid://shopify/FulfillmentOrderLineItem/401",
                                remainingQuantity = 2,
                                totalQuantity = 2,
                                variant = variant,
                              ),
                          ),
                        ),
                    ),
                ),
            ),
          ),
      ),
    fulfillments = emptyList(),
  )
}

/** Like [minimalOrder] but with explicit FO line remaining/total (for partial-fulfillment cases). */
internal fun orderWithFoQuantities(
  remaining: Int,
  total: Int = remaining,
  fulfillment: OrderDisplayFulfillmentStatus = OrderDisplayFulfillmentStatus.UNFULFILLED,
  financial: OrderDisplayFinancialStatus = OrderDisplayFinancialStatus.PAID,
): Order {
  val base = minimalOrder(fulfillment = fulfillment, financial = financial)
  val foLine =
    base.fulfillmentOrders.edges.single().node.lineItems.edges.single().node.copy(
      remainingQuantity = remaining,
      totalQuantity = total,
    )
  val fo =
    base.fulfillmentOrders.edges.single().node.copy(
      lineItems =
        FulfillmentOrderLineItemConnection(
          edges = listOf(FulfillmentOrderLineItemEdge(node = foLine)),
        ),
    )
  return base.copy(
    fulfillmentOrders =
      FulfillmentOrderConnection(
        edges = listOf(FulfillmentOrderEdge(node = fo)),
      ),
  )
}

/** Two open fulfillment orders, each with one variant (for incremental second-item cases). */
internal fun orderWithTwoVariantFulfillmentOrders(
  firstVariantId: Long = 101L,
  firstRemaining: Int = 1,
  firstTotal: Int = firstRemaining,
  firstFoId: Long = 301L,
  firstLineItemId: Long = 401L,
  secondVariantId: Long = 202L,
  secondRemaining: Int = 1,
  secondTotal: Int = secondRemaining,
  secondFoId: Long = 302L,
  secondLineItemId: Long = 402L,
): Order {
  val firstVariant =
    ProductVariant(
      id = "gid://shopify/ProductVariant/$firstVariantId",
      legacyResourceId = firstVariantId.toString(),
    )
  val secondVariant =
    ProductVariant(
      id = "gid://shopify/ProductVariant/$secondVariantId",
      legacyResourceId = secondVariantId.toString(),
    )
  val firstFo =
    FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/$firstFoId",
      status = FulfillmentOrderStatus.OPEN,
      lineItems =
        FulfillmentOrderLineItemConnection(
          edges =
            listOf(
              FulfillmentOrderLineItemEdge(
                node =
                  FulfillmentOrderLineItem(
                    id = "gid://shopify/FulfillmentOrderLineItem/$firstLineItemId",
                    remainingQuantity = firstRemaining,
                    totalQuantity = firstTotal,
                    variant = firstVariant,
                  ),
              ),
            ),
        ),
    )
  val secondFo =
    FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/$secondFoId",
      status = FulfillmentOrderStatus.OPEN,
      lineItems =
        FulfillmentOrderLineItemConnection(
          edges =
            listOf(
              FulfillmentOrderLineItemEdge(
                node =
                  FulfillmentOrderLineItem(
                    id = "gid://shopify/FulfillmentOrderLineItem/$secondLineItemId",
                    remainingQuantity = secondRemaining,
                    totalQuantity = secondTotal,
                    variant = secondVariant,
                  ),
              ),
            ),
        ),
    )
  return minimalOrder().copy(
    fulfillmentOrders =
      FulfillmentOrderConnection(
        edges = listOf(FulfillmentOrderEdge(node = firstFo), FulfillmentOrderEdge(node = secondFo)),
      ),
  )
}

/** [minimalOrder] as `orders/create` can find it: Shopify routes an order into fulfillment orders after creating it. */
internal fun orderWithoutFulfillmentOrders(): Order =
  minimalOrder().copy(fulfillmentOrders = FulfillmentOrderConnection(edges = emptyList()))

/**
 * [minimalOrder] carrying one existing fulfillment, for the cancel-then-recreate paths and for the
 * tracking-event sync, which finds its fulfillment by [trackingNumbers].
 */
internal fun orderWithFulfillment(id: Long, trackingNumbers: List<String> = emptyList()): Order =
  orderWithFulfillments(fulfillment(id, trackingNumbers))

internal fun orderWithFulfillments(vararg fulfillments: Fulfillment): Order =
  minimalOrder().copy(fulfillments = fulfillments.toList())

/** A fulfillment as `GetOrderForDss` loads it; live ([FulfillmentStatus.SUCCESS]) unless told otherwise. */
internal fun fulfillment(
  id: Long,
  trackingNumbers: List<String> = emptyList(),
  status: FulfillmentStatus = FulfillmentStatus.SUCCESS,
): Fulfillment =
  Fulfillment(
    id = "gid://shopify/Fulfillment/$id",
    legacyResourceId = id.toString(),
    status = status,
    trackingInfo = trackingNumbers.map { FulfillmentTrackingInfo(number = it) },
  )
