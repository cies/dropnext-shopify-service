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
import dropnext.graphql.generated.getorderfordss.PageInfo
import dropnext.graphql.generated.getorderfordss.ProductVariant


/**
 * What every connection of a fixture reports unless the case is about truncation. A snapshot with another page is a
 * failure, so a fixture that truncated by default would put every unrelated test on the unhappy path.
 */
internal val COMPLETE_PAGE = PageInfo(hasNextPage = false)

/** [moreLineItems], [moreFulfillmentOrders] and [moreFoLineItems] each make one connection report another page. */
internal fun minimalOrder(
  fulfillment: OrderDisplayFulfillmentStatus = OrderDisplayFulfillmentStatus.UNFULFILLED,
  financial: OrderDisplayFinancialStatus = OrderDisplayFinancialStatus.PAID,
  moreLineItems: Boolean = false,
  moreFulfillmentOrders: Boolean = false,
  moreFoLineItems: Boolean = false,
): Order {
  val variant =
    ProductVariant(
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
    createdAt = "2026-04-25T10:30:00+00:00",
    totalPriceSet =
      MoneyBag(
        shopMoney = MoneyV2(amount = "39.98", currencyCode = CurrencyCode.USD),
      ),
    displayFinancialStatus = financial,
    displayFulfillmentStatus = fulfillment,
    shippingAddress = null,
    lineItems = LineItemConnection(
      pageInfo = PageInfo(hasNextPage = moreLineItems),
      edges = listOf(LineItemEdge(node = lineItem)),
    ),
    fulfillmentOrders =
      FulfillmentOrderConnection(
        pageInfo = PageInfo(hasNextPage = moreFulfillmentOrders),
        edges =
          listOf(
            FulfillmentOrderEdge(
              node =
                FulfillmentOrder(
                  id = "gid://shopify/FulfillmentOrder/301",
                  status = FulfillmentOrderStatus.OPEN,
                  lineItems =
                    FulfillmentOrderLineItemConnection(
                      pageInfo = PageInfo(hasNextPage = moreFoLineItems),
                      edges =
                        listOf(
                          FulfillmentOrderLineItemEdge(
                            node =
                              FulfillmentOrderLineItem(
                                id = "gid://shopify/FulfillmentOrderLineItem/401",
                                remainingQuantity = 2,
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

/** Like [minimalOrder] but with an explicit remaining quantity on the FO line (for partial-fulfillment cases). */
internal fun orderWithFoQuantities(
  remaining: Int,
  fulfillment: OrderDisplayFulfillmentStatus = OrderDisplayFulfillmentStatus.UNFULFILLED,
  financial: OrderDisplayFinancialStatus = OrderDisplayFinancialStatus.PAID,
): Order {
  val base = minimalOrder(fulfillment = fulfillment, financial = financial)
  val foLine =
    base.fulfillmentOrders.edges.single().node.lineItems.edges.single().node.copy(
      remainingQuantity = remaining,
    )
  val fo =
    base.fulfillmentOrders.edges.single().node.copy(
      lineItems =
        FulfillmentOrderLineItemConnection(
          pageInfo = COMPLETE_PAGE,
          edges = listOf(FulfillmentOrderLineItemEdge(node = foLine)),
        ),
    )
  return base.copy(
    fulfillmentOrders =
      FulfillmentOrderConnection(
        pageInfo = COMPLETE_PAGE,
        edges = listOf(FulfillmentOrderEdge(node = fo)),
      ),
  )
}

/** Two open fulfillment orders, each with one variant (for incremental second-item cases). */
internal fun orderWithTwoVariantFulfillmentOrders(
  firstVariantId: Long = 101L,
  firstRemaining: Int = 1,
  firstFoId: Long = 301L,
  firstLineItemId: Long = 401L,
  secondVariantId: Long = 202L,
  secondRemaining: Int = 1,
  secondFoId: Long = 302L,
  secondLineItemId: Long = 402L,
): Order {
  val firstVariant =
    ProductVariant(
      legacyResourceId = firstVariantId.toString(),
    )
  val secondVariant =
    ProductVariant(
      legacyResourceId = secondVariantId.toString(),
    )
  val firstFo =
    FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/$firstFoId",
      status = FulfillmentOrderStatus.OPEN,
      lineItems =
        FulfillmentOrderLineItemConnection(
          pageInfo = COMPLETE_PAGE,
          edges =
            listOf(
              FulfillmentOrderLineItemEdge(
                node =
                  FulfillmentOrderLineItem(
                    id = "gid://shopify/FulfillmentOrderLineItem/$firstLineItemId",
                    remainingQuantity = firstRemaining,
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
          pageInfo = COMPLETE_PAGE,
          edges =
            listOf(
              FulfillmentOrderLineItemEdge(
                node =
                  FulfillmentOrderLineItem(
                    id = "gid://shopify/FulfillmentOrderLineItem/$secondLineItemId",
                    remainingQuantity = secondRemaining,
                    variant = secondVariant,
                  ),
              ),
            ),
        ),
    )
  return minimalOrder().copy(
    fulfillmentOrders =
      FulfillmentOrderConnection(
        pageInfo = COMPLETE_PAGE,
        edges = listOf(FulfillmentOrderEdge(node = firstFo), FulfillmentOrderEdge(node = secondFo)),
      ),
  )
}

/** [minimalOrder] as `orders/create` can find it: Shopify routes an order into fulfillment orders after creating it. */
internal fun orderWithoutFulfillmentOrders(): Order =
  minimalOrder().copy(fulfillmentOrders = FulfillmentOrderConnection(pageInfo = COMPLETE_PAGE, edges = emptyList()))

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
    status = status,
    trackingInfo = trackingNumbers.map { FulfillmentTrackingInfo(number = it) },
  )
