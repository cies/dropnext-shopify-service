package dropnext.dss.mapper

import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.OrderLineItem
import dropnext.dss.contract.ShippingAddress
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithoutFulfillmentOrders
import dropnext.dss.testutil.helper.orderToCreateShopifyOrderRequest
import dropnext.graphql.generated.enums.CountryCode
import dropnext.graphql.generated.enums.CurrencyCode
import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.dss.testutil.fixture.COMPLETE_PAGE
import dropnext.graphql.generated.getorderfordss.LineItem
import dropnext.graphql.generated.getorderfordss.LineItemConnection
import dropnext.graphql.generated.getorderfordss.LineItemEdge
import dropnext.graphql.generated.getorderfordss.MailingAddress
import dropnext.graphql.generated.getorderfordss.MoneyBag
import dropnext.graphql.generated.getorderfordss.MoneyV2
import dropnext.graphql.generated.getorderfordss.Order
import org.junit.jupiter.api.Test


class MapOrderForMonolithTest {

  private val orderId = ShopifyOrderId(1001L)

  /**
   * The whole request, so a field nobody asserts on its own is still pinned. The two snapshot titles cross over on
   * purpose: Shopify's line `name` is the variant's title ("T-Shirt - Blue"), its `title` the product's.
   */
  @Test
  fun `maps the minimal order to the request the monolith expects`() {
    val order = minimalOrder().copy(email = "buyer@example.com")

    val expected = CreateShopifyOrderRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      name = "#1001",
      financialStatus = "Paid",
      fulfillmentStatus = null,
      createdAt = "2026-04-25T10:30:00Z",
      shippingAddress = ShippingAddress(
        firstName = null,
        lastName = null,
        address1 = "",
        address2 = null,
        city = "",
        province = null,
        provinceCode = null,
        countryCode = "",
        zip = null,
        phone = null,
      ),
      lineItems = listOf(
        OrderLineItem(
          shopifyLineItemId = 201L,
          productVariantId = 101L,
          quantity = 2,
          snapshotOfVariantTitle = "T-Shirt - Blue",
          snapshotOfProductTitle = "T-Shirt",
          snapshotOfPriceAsString = "19.99",
        ),
      ),
      totalAsString = "39.98",
      currency = "USD",
      email = "buyer@example.com",
    )
    assert(mapOrderForMonolith("acme", orderId, order) == MonolithOrderMapping(expected, omittedLineItems = emptyList()))
  }

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
    val expected = ShippingAddress(
      firstName = "Ada",
      lastName = "Lovelace",
      address1 = "10 Downing St",
      address2 = "Apt 2",
      city = "London",
      province = "England",
      provinceCode = "ENG",
      countryCode = "GB",
      zip = "SW1A 2AA",
      phone = "+44 20 7946 0958",
    )
    assert(req.shippingAddress == expected)
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
    val order = minimalOrder().copy(totalPriceSet = MoneyBag(shopMoney = MoneyV2(amount = "0.00", currencyCode = CurrencyCode.USD)))
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
    val order = minimalOrder().copy(totalPriceSet = MoneyBag(shopMoney = MoneyV2(amount = "n/a", currencyCode = CurrencyCode.USD)))
    val req = orderToCreateShopifyOrderRequest("dropnext-staging", order)
    // The fixture's one line: 19.99 × 2.
    assert(req.totalAsString == "39.98")
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
    copy(lineItems = LineItemConnection(pageInfo = COMPLETE_PAGE, edges = listOf(LineItemEdge(node = change(lineItems.edges.single().node)))))

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
