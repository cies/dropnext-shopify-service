package dropnext.dss.domain.fulfillment

import dropnext.dss.testutil.fixture.openFulfillmentOrder
import dropnext.dss.testutil.fixture.orderWithFulfillmentOrders
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.dss.testutil.fixture.COMPLETE_PAGE
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.ProductVariant
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import org.junit.jupiter.api.Test


class OpenFulfillmentLinesTest {

  @Test
  fun `indexes the lines of open fulfillment orders by variant id`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(foId = 301L, lineItemId = 401L, variantId = 101L, remaining = 2),
      openFulfillmentOrder(foId = 302L, lineItemId = 402L, variantId = 202L, remaining = 3, status = FulfillmentOrderStatus.IN_PROGRESS),
    )
    val lines = openFulfillmentLines(order)
    assert(lines.keys == setOf(101L, 202L))
    val first = lines.getValue(101L).single()
    assert(first.fulfillmentOrderId == "gid://shopify/FulfillmentOrder/301")
    assert(first.line.id == "gid://shopify/FulfillmentOrderLineItem/401")
    assert(lines.getValue(202L).single().fulfillmentOrderId == "gid://shopify/FulfillmentOrder/302")
  }

  @Test
  fun `leaves out closed and canceled fulfillment orders`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(foId = 301L, lineItemId = 401L, variantId = 101L, remaining = 2, status = FulfillmentOrderStatus.CLOSED),
      openFulfillmentOrder(foId = 302L, lineItemId = 402L, variantId = 202L, remaining = 2, status = FulfillmentOrderStatus.CANCELLED),
    )
    assert(openFulfillmentLines(order).isEmpty())
  }

  @Test
  fun `leaves out a line whose variant id cannot be read`() {
    val unreadable = FulfillmentOrderLineItem(
      id = "gid://shopify/FulfillmentOrderLineItem/401",
      remainingQuantity = 5,
      variant = ProductVariant(legacyResourceId = "not-a-number"),
    )
    val fulfillmentOrder = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = FulfillmentOrderLineItemConnection(pageInfo = COMPLETE_PAGE, edges = listOf(FulfillmentOrderLineItemEdge(node = unreadable))),
    )
    assert(openFulfillmentLines(orderWithFulfillmentOrders(fulfillmentOrder)).isEmpty())
  }

  @Test
  fun `keeps Graphql order inside a variant's group`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(foId = 301L, lineItemId = 401L, variantId = 101L, remaining = 1),
      openFulfillmentOrder(foId = 302L, lineItemId = 402L, variantId = 101L, remaining = 5),
      openFulfillmentOrder(foId = 303L, lineItemId = 403L, variantId = 101L, remaining = 2),
    )
    val ids = openFulfillmentLines(order).getValue(101L).map { it.line.id }
    assert(ids == listOf("gid://shopify/FulfillmentOrderLineItem/401", "gid://shopify/FulfillmentOrderLineItem/402", "gid://shopify/FulfillmentOrderLineItem/403"))
  }

  @Test
  fun `an unknown variant has no lines`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(foId = 301L, lineItemId = 401L, variantId = 101L, remaining = 2))
    assert(openFulfillmentLines(order)[999L].orEmpty().isEmpty())
  }

  @Test
  fun `availableGiven an empty plan is the snapshot remaining quantity`() {
    val line = openFulfillmentLines(orderWithFulfillmentOrders(openFulfillmentOrder(foId = 301L, lineItemId = 401L, variantId = 101L, remaining = 3)))
      .getValue(101L).single()
    assert(line.availableGiven(emptyList()) == 3)
  }

  @Test
  fun `availableGiven subtracts what the plan already claims on the same line`() {
    val line = openFulfillmentLines(orderWithFulfillmentOrders(openFulfillmentOrder(foId = 301L, lineItemId = 401L, variantId = 101L, remaining = 3)))
      .getValue(101L).single()
    val planned = listOf(
      FulfillmentOrderLineItemInput(id = "gid://shopify/FulfillmentOrderLineItem/401", quantity = 1),
      FulfillmentOrderLineItemInput(id = "gid://shopify/FulfillmentOrderLineItem/401", quantity = 1),
    )
    assert(line.availableGiven(planned) == 1)
  }

  @Test
  fun `availableGiven ignores what the plan claims on another line`() {
    val line = openFulfillmentLines(orderWithFulfillmentOrders(openFulfillmentOrder(foId = 301L, lineItemId = 401L, variantId = 101L, remaining = 3)))
      .getValue(101L).single()
    val planned = listOf(FulfillmentOrderLineItemInput(id = "gid://shopify/FulfillmentOrderLineItem/402", quantity = 2))
    assert(line.availableGiven(planned) == 3)
  }

  @Test
  fun `isOpenForFulfillment is true for OPEN`() {
    assert(FulfillmentOrderStatus.OPEN.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is true for IN_PROGRESS`() {
    assert(FulfillmentOrderStatus.IN_PROGRESS.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is false for CLOSED`() {
    assert(!FulfillmentOrderStatus.CLOSED.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is false for CANCELLED`() {
    assert(!FulfillmentOrderStatus.CANCELLED.isOpenForFulfillment())
  }

  @Test
  fun `isOpenForFulfillment is false for INCOMPLETE`() {
    assert(!FulfillmentOrderStatus.INCOMPLETE.isOpenForFulfillment())
  }

}
