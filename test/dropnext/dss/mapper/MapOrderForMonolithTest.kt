package dropnext.dss.mapper

import dropnext.dss.contract.OrderLineItem
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithoutFulfillmentOrders
import dropnext.dss.testutil.helper.orderToCreateShopifyOrderRequest
import dropnext.graphql.generated.enums.CountryCode
import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.LineItem
import dropnext.graphql.generated.getorderfordss.LineItemConnection
import dropnext.graphql.generated.getorderfordss.LineItemEdge
import dropnext.graphql.generated.getorderfordss.MailingAddress
import dropnext.graphql.generated.getorderfordss.Order
import kotlin.test.Test


class MapOrderForMonolithTest {

  private val orderId = ShopifyOrderId(1001L)

  @Test
  fun `maps financial status to PascalCase`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder(financial = OrderDisplayFinancialStatus.PAID))
    assert(req.financialStatus == "Paid")
  }

  @Test
  fun `maps unfulfilled to null fulfillment_status`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.UNFULFILLED),
      )
    assert(req.fulfillmentStatus == null)
  }

  @Test
  fun `maps fulfilled to fulfilled string`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.FULFILLED),
      )
    assert(req.fulfillmentStatus == "Fulfilled")
  }

  @Test
  fun `normalizes created_at to UTC Z`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder())
    assert(req.createdAt == "2026-04-25T10:30:00Z")
  }

  /** Shopify routes an order into fulfillment orders after creating it, so `orders/create` can find none yet. */
  @Test
  fun `an order without fulfillment orders maps its line and reports no omission`() {
    val mapping = mapOrderForMonolith("dropnext-staging", orderId, orderWithoutFulfillmentOrders())
    assert(mapping.request.lineItems.single().productVariantId == 101L)
    assert(mapping.omittedLineItems.isEmpty())
  }

  @Test
  fun `maps PARTIALLY_FULFILLED to partial`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.PARTIALLY_FULFILLED),
      )
    assert(req.fulfillmentStatus == "Partial")
  }

  @Test
  fun `maps IN_PROGRESS to partial`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.IN_PROGRESS),
      )
    assert(req.fulfillmentStatus == "Partial")
  }

  @Test
  fun `maps RESTOCKED to restocked`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.RESTOCKED),
      )
    assert(req.fulfillmentStatus == "Restocked")
  }

  @Test
  fun `maps ON_HOLD to null`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.ON_HOLD),
      )
    assert(req.fulfillmentStatus == null)
  }

  @Test
  fun `maps unknown fulfillment status to null`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(fulfillment = OrderDisplayFulfillmentStatus.__UNKNOWN_VALUE),
      )
    assert(req.fulfillmentStatus == null)
  }

  @Test
  fun `maps unknown financial status to unknown string`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(financial = OrderDisplayFinancialStatus.__UNKNOWN_VALUE),
      )
    assert(req.financialStatus == "Unknown")
  }

  @Test
  fun `maps REFUNDED financial status to refunded`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(financial = OrderDisplayFinancialStatus.REFUNDED),
      )
    assert(req.financialStatus == "Refunded")
  }

  @Test
  fun `maps PARTIALLY_PAID financial status to PartiallyPaid`() {
    val req =
      orderToCreateShopifyOrderRequest(
        "dropnext-staging",
        minimalOrder(financial = OrderDisplayFinancialStatus.PARTIALLY_PAID),
      )
    assert(req.financialStatus == "PartiallyPaid")
  }

  @Test
  fun `omits line items whose variant is null`() {
    val base = minimalOrder()
    val withNullVariant = base.copy(
      lineItems = dropnext.graphql.generated.getorderfordss.LineItemConnection(
        edges = listOf(
          dropnext.graphql.generated.getorderfordss.LineItemEdge(
            node = base.lineItems.edges.single().node.copy(variant = null),
          ),
        ),
      ),
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", withNullVariant)
    assert(req.lineItems.isEmpty())
  }

  @Test
  fun `maps shippingAddress when present`() {
    val mailing = MailingAddress(
      firstName = "Ada",
      lastName = "Lovelace",
      address1 = "10 Downing St",
      address2 = "Apt 2",
      city = "London",
      province = "England",
      provinceCode = "ENG",
      countryCodeV2 = CountryCode.GB,
      zip = "SW1A 2AA",
      phone = "+44 20 7946 0958",
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(shippingAddress = mailing))
    val s = req.shippingAddress
    assert(s.firstName == "Ada")
    assert(s.lastName == "Lovelace")
    assert(s.address1 == "10 Downing St")
    assert(s.address2 == "Apt 2")
    assert(s.city == "London")
    assert(s.province == "England")
    assert(s.provinceCode == "ENG")
    assert(s.countryCode == "GB")
    assert(s.zip == "SW1A 2AA")
    assert(s.phone == "+44 20 7946 0958")
  }

  @Test
  fun `shippingAddress null mailing fields are coerced to empty strings on non-null DTO fields`() {
    val mailing = MailingAddress(
      address1 = null,
      city = null,
      countryCodeV2 = null,
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(shippingAddress = mailing))
    assert(req.shippingAddress.address1 == "")
    assert(req.shippingAddress.city == "")
    assert(req.shippingAddress.countryCode == "")
  }

  @Test
  fun `default shipping address is used when order has none`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(shippingAddress = null))
    assert(req.shippingAddress.address1 == "")
    assert(req.shippingAddress.firstName == null)
  }

  @Test
  fun `maps line item unit price and order total from Shopify`() {
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder())
    assert(req.lineItems.single().snapshotOfPriceAsString == "19.99")
    assert(req.totalAsString == "39.98")
  }

  @Test
  fun `resolveOrderTotalAsString uses Shopify total when positive`() {
    assert(
      resolveOrderTotalAsString(
        orderTotalAmount = "39.98",
        lineItems = emptyList(),
        currencyCode = "USD",
      ) == "39.98",
    )
  }

  /** A fully discounted order really costs nothing; the undiscounted line sum would report money never paid. */
  @Test
  fun `resolveOrderTotalAsString keeps a zero order total`() {
    val lineItems = listOf(
      OrderLineItem(
        shopifyLineItemId = 1L,
        productVariantId = 1L,
        quantity = 2,
        snapshotOfVariantTitle = "Item",
        snapshotOfProductTitle = "Product",
        snapshotOfPriceAsString = "19.99",
      ),
    )
    assert(resolveOrderTotalAsString(orderTotalAmount = "0.00", lineItems = lineItems, currencyCode = "USD") == "0.00")
  }

  @Test
  fun `keeps a zero totalPriceSet instead of summing the undiscounted lines`() {
    val order = minimalOrder().copy(
      totalPriceSet = dropnext.graphql.generated.getorderfordss.MoneyBag(
        shopMoney = dropnext.graphql.generated.getorderfordss.MoneyV2(
          amount = "0.00",
          currencyCode = dropnext.graphql.generated.enums.CurrencyCode.USD,
        ),
      ),
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    assert(req.totalAsString == "0.00")
    assert(req.lineItems.isNotEmpty())
  }

  @Test
  fun `resolveOrderTotalAsString falls back to the line sum for a negative amount`() {
    val lineItems = listOf(
      OrderLineItem(
        shopifyLineItemId = 1L,
        productVariantId = 1L,
        quantity = 2,
        snapshotOfVariantTitle = "Item",
        snapshotOfProductTitle = "Product",
        snapshotOfPriceAsString = "19.99",
      ),
    )
    assert(resolveOrderTotalAsString(orderTotalAmount = "-1.00", lineItems = lineItems, currencyCode = "USD") == "39.98")
  }

  @Test
  fun `falls back to lineItems sum when totalPriceSet is unparseable`() {
    val order = minimalOrder().copy(
      totalPriceSet = dropnext.graphql.generated.getorderfordss.MoneyBag(
        shopMoney = dropnext.graphql.generated.getorderfordss.MoneyV2(
          amount = "n/a",
          currencyCode = dropnext.graphql.generated.enums.CurrencyCode.USD,
        ),
      ),
    )
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    val lineSumMinor = req.lineItems.sumOf {
      shopifyAmountToMinorUnits(it.snapshotOfPriceAsString, "USD") * it.quantity.toLong()
    }
    assert(req.totalAsString == minorUnitsToShopifyAmount(lineSumMinor, "USD"))
  }

  /** A snapshot without a readable id used to be sent as order `0`; the id now comes from the gid the caller loaded. */
  @Test
  fun `the order is keyed by the id the caller names`() {
    val mapping = mapOrderForMonolith("dropnext-staging", ShopifyOrderId(4242L), minimalOrder())
    assert(mapping.request.shopifyOrderId == 4242L)
  }

  // ---------- what is left out, and why ----------

  @Test
  fun `a fully mapped order reports no omissions`() {
    assert(mapOrderForMonolith("dropnext-staging", orderId, minimalOrder()).omittedLineItems.isEmpty())
  }

  @Test
  fun `a line without a variant is omitted as such`() {
    val order = minimalOrder().withSingleLineItem { it.copy(variant = null) }
    val mapping = mapOrderForMonolith("dropnext-staging", orderId, order)
    assert(mapping.request.lineItems.isEmpty())
    assert(mapping.omittedLineItems == listOf(OmittedOrderLineItem("gid://shopify/LineItem/201", OrderLineItemOmission.NO_VARIANT)))
  }

  @Test
  fun `a line whose variant id is not numeric is omitted as such`() {
    val order = minimalOrder().withSingleLineItem { it.copy(variant = it.variant!!.copy(legacyResourceId = "abc")) }
    val mapping = mapOrderForMonolith("dropnext-staging", orderId, order)
    assert(mapping.omittedLineItems.single().reason == OrderLineItemOmission.UNPARSEABLE_VARIANT_ID)
  }

  @Test
  fun `a line whose own id carries no number is omitted as such`() {
    val order = minimalOrder().withSingleLineItem { it.copy(id = "gid://shopify/LineItem/") }
    val mapping = mapOrderForMonolith("dropnext-staging", orderId, order)
    assert(mapping.omittedLineItems.single() == OmittedOrderLineItem("gid://shopify/LineItem/", OrderLineItemOmission.UNPARSEABLE_LINE_ITEM_ID))
  }

  private fun Order.withSingleLineItem(change: (LineItem) -> LineItem): Order =
    copy(lineItems = LineItemConnection(edges = listOf(LineItemEdge(node = change(lineItems.edges.single().node)))))

  /** Protected customer data the monolith keeps with the order; an order without a customer has none. */
  @Test
  fun `passes the order's email on, and none when Shopify has none`() {
    val withEmail = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(email = "buyer@example.com"))
    val withoutEmail = orderToCreateShopifyOrderRequest("dropnext-staging", minimalOrder().copy(email = null))
    assert(withEmail.email == "buyer@example.com")
    assert(withoutEmail.email == null)
  }

  @Test
  fun `falls back to raw createdAt when parsing fails`() {

    val order = minimalOrder().copy(createdAt = "not-a-date")
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    assert(req.createdAt == "not-a-date")
  }
}
