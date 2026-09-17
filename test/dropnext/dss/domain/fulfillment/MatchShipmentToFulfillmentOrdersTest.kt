package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.diagramCrossFoShipment
import dropnext.dss.testutil.fixture.foLineItemConnection
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.openFulfillmentOrder
import dropnext.dss.testutil.fixture.orderWithFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.dss.testutil.fixture.COMPLETE_PAGE
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import org.junit.jupiter.api.Test


class MatchShipmentToFulfillmentOrdersTest {

  @Test
  fun `matches open fulfillment order line items`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.size == 1)
    assert(ok.groups.values.single().single().quantity == 1)
    assert(ok.skipped.isEmpty())
  }

  @Test
  fun `ignores closed fulfillment orders`() {
    val closed = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.CLOSED,
      lineItems = foLineItemConnection(variantId = 101L, remaining = 2),
    )
    val order = orderWithFulfillmentOrders(closed)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.single().reason == SkipReason.NO_OPEN_FO)
  }

  @Test
  fun `returns user error when quantity exceeds remainingQuantity`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `partial match skips unknown variant`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val shipment = shipment(lines = listOf(101L to 1, 999L to 1))
    val result = matchOneShipment(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 1)
    assert(result.groups.values.single().single().quantity == 1)
    assert(result.skipped.size == 1)
    assert(result.skipped.single().productVariantId == 999L)
    assert(result.skipped.single().reason == SkipReason.NO_OPEN_FO)
  }

  @Test
  fun `all lines skipped returns empty groups`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(variantId = 999L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.size == 1)
  }

  @Test
  fun `treats IN_PROGRESS fulfillment orders as open`() {
    val fo = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.IN_PROGRESS,
      lineItems = foLineItemConnection(variantId = 101L, remaining = 2),
    )
    val order = orderWithFulfillmentOrders(fo)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    // An all-skipped result is `Ok` too, so only the planned line tells an open fulfillment order from a closed one.
    assert(result == ShipmentMatchResult.Ok(groups = mapOf(fulfillmentOrderGid(301L) to listOf(lineInput(401L, 1))), skipped = emptyList()))
  }

  @Test
  fun `groups line items across multiple fulfillment orders`() {
    val fo1 = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItemConnection(variantId = 101L, remaining = 5),
    )
    val fo2 = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItemConnection(variantId = 202L, remaining = 5),
    )
    val order = orderWithFulfillmentOrders(fo1, fo2)
    val shipment = shipment(tracking = "TRK", lines = listOf(101L to 2, 202L to 1))
    val result = matchOneShipment(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 2)
    assert(result.groups.entries.single { it.key.endsWith("301") }.value.single().quantity == 2)
    assert(result.groups.entries.single { it.key.endsWith("302") }.value.single().quantity == 1)
  }

  @Test
  fun `duplicate variant rows aggregated`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 5))
    val shipment = Shipment(
      trackingNumber = "1Z999",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(
        ShipmentLineItem(productVariantId = 101L, quantity = 2),
        ShipmentLineItem(productVariantId = 101L, quantity = 1),
      ),
    )
    val normalized = normalizeShipmentLineItems(shipment)
    assert(normalized.size == 1)
    assert(normalized.single().quantity == 3)

    val result = matchOneShipment(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 1)
    assert(result.groups.values.single().single().quantity == 3)
  }

  @Test
  fun `zero remainingQuantity skips as ZERO_REMAINING`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 0))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.single().reason == SkipReason.ZERO_REMAINING)
  }

  @Test
  fun `cross-shipment allocation within limits succeeds dry-run`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 3))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 2),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(
      result == DryRunResult.Ok(
        listOf(
          ShipmentMatchResult.Ok(groups = mapOf(fulfillmentOrderGid(301L) to listOf(lineInput(401L, 2))), skipped = emptyList()),
          ShipmentMatchResult.Ok(groups = mapOf(fulfillmentOrderGid(301L) to listOf(lineInput(401L, 1))), skipped = emptyList()),
        ),
      ),
    )
  }

  @Test
  fun `an unreadable variant id on one line leaves the variant's readable line matched`() {
    val unreadableLine =
      FulfillmentOrderLineItem(
        id = "gid://shopify/FulfillmentOrderLineItem/401",
        remainingQuantity = 5,
        variant = ProductVariant(
          legacyResourceId = "not-a-number",
        ),
      )
    val unreadableFo =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = FulfillmentOrderLineItemConnection(
          pageInfo = COMPLETE_PAGE,
          edges = listOf(FulfillmentOrderLineItemEdge(node = unreadableLine)),
        ),
      )
    val readableFo = openFulfillmentOrder(variantId = 101L, remaining = 5, foId = 302L, lineItemId = 402L)
    val order = orderWithFulfillmentOrders(unreadableFo, readableFo)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result == ShipmentMatchResult.Ok(groups = mapOf(fulfillmentOrderGid(302L) to listOf(lineInput(402L, 1))), skipped = emptyList()))
  }

  @Test
  fun `closed FO with zero remaining skipped`() {
    val closedFo = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.CLOSED,
      lineItems = foLineItemConnection(variantId = 101L, remaining = 0),
    )
    val openFoZero = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItemConnection(lineItemId = 402L, variantId = 101L, remaining = 0),
    )
    val order = orderWithFulfillmentOrders(closedFo, openFoZero)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.single().reason == SkipReason.ZERO_REMAINING)
  }

  @Test
  fun `prefers fulfillment order with highest available remainingQuantity`() {
    val foLow = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItemConnection(lineItemId = 401L, variantId = 101L, remaining = 1),
    )
    val foHigh = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItemConnection(lineItemId = 402L, variantId = 101L, remaining = 5),
    )
    val order = orderWithFulfillmentOrders(foLow, foHigh)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2))
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.keys.single().endsWith("302"))
  }

  @Test
  fun `zero quantity (bypassed validation) returns user error`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 0))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `skipped line includes variant id and tracking number`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(variantId = 999L, quantity = 1))
    val ok = result as ShipmentMatchResult.Ok
    val skipped = ok.skipped.single()
    assert(skipped.productVariantId == 999L)
    assert(skipped.trackingNumber == "1Z999")
  }

  @Test
  fun `dry-run reports total skipped lines across shipments`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val shipments =
      listOf(
        Shipment(
          trackingNumber = "TRK-1",
          carrier = "UPS",
          trackingUrl = null,
          lineItems = listOf(
            ShipmentLineItem(productVariantId = 101L, quantity = 1),
            ShipmentLineItem(productVariantId = 999L, quantity = 1),
          ),
        ),
        shipment(tracking = "TRK-2", variantId = 888L, quantity = 1),
      )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.Ok
    assert(result.perShipment.sumOf { it.skipped.size } == 2)
    assert(result.perShipment.size == 2)
    assert(result.perShipment.first().skipped.size == 1)
    assert(result.perShipment.last().skipped.size == 1)
  }

  @Test
  fun `groups are keyed by the fulfillment order gid in first-seen order`() {
    val result =
      matchOneShipment(diagramCrossFoOrder(), diagramCrossFoShipment()) as ShipmentMatchResult.Ok
    assert(
      result.groups.keys.toList() == listOf(
        "gid://shopify/FulfillmentOrder/301",
        "gid://shopify/FulfillmentOrder/302"
      )
    )
    assert(result.skipped.isEmpty())
  }

  @Test
  fun `a unit already planned on the line counts against what a later shipment may take`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val planned = listOf(
      FulfillmentOrderLineItemInput(
        id = "gid://shopify/FulfillmentOrderLineItem/401",
        quantity = 1
      )
    )
    val result = matchShipmentToFulfillmentOrders(
      shipment(variantId = 101L, quantity = 2),
      openFulfillmentLines(order),
      planned
    )
    assert(result is ShipmentMatchResult.UserError)
    assert("variant 101 requested quantity 2 exceeds remaining 1" in (result as ShipmentMatchResult.UserError).messages.single())
  }

  /**
   * The skip reads Shopify's snapshot and the error reads the plan: a unit an earlier shipment of this payload
   * claimed is a payload problem to refuse, not a line Shopify already fulfilled.
   */
  @Test
  fun `a unit an earlier shipment of the payload claimed is an error, not a zero_remaining skip`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.UserError
    assert("variant 101 requested quantity 1 exceeds remaining 0" in result.messages.single())
  }

  // ---------- one variant, several places it could be filed ----------

  /**
   * One variant can sit on several open lines: two line items of the order, or one line routed to two locations. A
   * shipment line that no single one can serve is spread over them rather than refused.
   */
  @Test
  fun `a quantity larger than any single open line is spread over the lines that cover it together`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 301L, lineItemId = 401L),
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 302L, lineItemId = 402L),
    )
    val ok = matchOneShipment(order, shipment(variantId = 101L, quantity = 2)) as ShipmentMatchResult.Ok
    assert(
      ok.groups.mapValues { (_, inputs) -> inputs.map { it.id to it.quantity } } == mapOf(
        "gid://shopify/FulfillmentOrder/301" to listOf("gid://shopify/FulfillmentOrderLineItem/401" to 1),
        "gid://shopify/FulfillmentOrder/302" to listOf("gid://shopify/FulfillmentOrderLineItem/402" to 1),
      ),
    )
  }

  /** Taking the line with the most left first keeps a spread on as few lines as it can be. */
  @Test
  fun `a spread takes all of the line with the most left before the next, the first in Graphql order on a tie`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(variantId = 101L, remaining = 2, foId = 301L, lineItemId = 401L),
      openFulfillmentOrder(variantId = 101L, remaining = 3, foId = 302L, lineItemId = 402L),
      openFulfillmentOrder(variantId = 101L, remaining = 2, foId = 303L, lineItemId = 403L),
    )
    val ok = matchOneShipment(order, shipment(variantId = 101L, quantity = 4)) as ShipmentMatchResult.Ok
    assert(
      ok.groups.values.flatten().map { it.id to it.quantity } == listOf(
        "gid://shopify/FulfillmentOrderLineItem/402" to 3,
        "gid://shopify/FulfillmentOrderLineItem/401" to 1,
      ),
    )
  }

  @Test
  fun `a quantity above what the variant's open lines have left together is refused, naming that total`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 301L, lineItemId = 401L),
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 302L, lineItemId = 402L),
    )
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 3))
    assert("variant 101 requested quantity 3 exceeds remaining 2" in (result as ShipmentMatchResult.UserError).messages.single())
  }

  @Test
  fun `units a spread claimed count against a later shipment of the payload`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 301L, lineItemId = 401L),
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 302L, lineItemId = 402L),
    )
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 2),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.UserError
    assert("variant 101 requested quantity 1 exceeds remaining 0" in result.messages.single())
  }

  @Test
  fun `the same variant on two lines of one open fulfillment order takes the line with more remaining`() {
    val variant = ProductVariant(legacyResourceId = "101")
    val fulfillmentOrder = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = FulfillmentOrderLineItemConnection(
        pageInfo = COMPLETE_PAGE,
        edges = listOf(
          FulfillmentOrderLineItemEdge(
            node = FulfillmentOrderLineItem(
              id = "gid://shopify/FulfillmentOrderLineItem/401",
              remainingQuantity = 1,
              variant = variant
            )
          ),
          FulfillmentOrderLineItemEdge(
            node = FulfillmentOrderLineItem(
              id = "gid://shopify/FulfillmentOrderLineItem/402",
              remainingQuantity = 4,
              variant = variant
            )
          ),
        ),
      ),
    )
    val result = matchOneShipment(
      orderWithFulfillmentOrders(fulfillmentOrder),
      shipment(variantId = 101L, quantity = 3)
    )
    val ok = result as ShipmentMatchResult.Ok
    val input = ok.groups.values.single().single()
    assert(input.id == "gid://shopify/FulfillmentOrderLineItem/402")
    assert(input.quantity == 3)
  }

  /** A line whose variant id cannot be read must not change what is said about another variant. */
  @Test
  fun `an unreadable variant id on an open line does not change the skip reason of another variant`() {
    val unreadable = FulfillmentOrderLineItem(
      id = "gid://shopify/FulfillmentOrderLineItem/401",
      remainingQuantity = 5,
      variant = ProductVariant(
        legacyResourceId = "not-a-number"
      ),
    )
    val fulfillmentOrder = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = FulfillmentOrderLineItemConnection(
        pageInfo = COMPLETE_PAGE,
        edges = listOf(
          FulfillmentOrderLineItemEdge(
            node = unreadable
          )
        )
      ),
    )
    val result = matchOneShipment(
      orderWithFulfillmentOrders(fulfillmentOrder),
      shipment(variantId = 999L, quantity = 1)
    )
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.single().reason == SkipReason.NO_OPEN_FO)
  }

  // ---------- a tracking number already on a live fulfillment ----------

  @Test
  fun `a shipment whose tracking number is on a live fulfillment is skipped whole and plans nothing`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
      .copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val result = dryRunAllShipments(order, listOf(shipment(tracking = "TRK-A", variantId = 101L, quantity = 1))) as DryRunResult.Ok
    val skipped = result.perShipment.single()
    assert(skipped.alreadyFulfilledBy.map { it.id } == listOf("gid://shopify/Fulfillment/8000"))
    assert(skipped.groups.isEmpty())
    assert(skipped.skipped.isEmpty())
  }

  /** The re-send the monolith makes after a partial failure: the shipment already synced must not take the unit the next one needs. */
  @Test
  fun `a shipment skipped by its tracking number claims no quantity from a later shipment of the payload`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
      .copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val shipments = listOf(
      shipment(tracking = "TRK-A", variantId = 101L, quantity = 1),
      shipment(tracking = "TRK-B", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.Ok
    assert(result.perShipment.last().groups.values.flatten().single().quantity == 1)
  }

  @Test
  fun `a shipment on a live fulfillment is skipped rather than refused for a quantity no longer left`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
      .copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val result = dryRunAllShipments(order, listOf(shipment(tracking = "TRK-A", variantId = 101L, quantity = 2)))
    assert(result is DryRunResult.Ok)
  }

  @Test
  fun `a shipment whose tracking number is only on a cancelled fulfillment is matched like a new one`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
      .copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"), status = FulfillmentStatus.CANCELLED)))
    val result = dryRunAllShipments(order, listOf(shipment(tracking = "TRK-A", variantId = 101L, quantity = 1))) as DryRunResult.Ok
    val matched = result.perShipment.single()
    assert(matched.alreadyFulfilledBy.isEmpty())
    assert(matched.groups.values.flatten().single().quantity == 1)
  }

  private fun fulfillmentOrderGid(id: Long): String = "gid://shopify/FulfillmentOrder/$id"

  private fun lineInput(lineItemId: Long, quantity: Int): FulfillmentOrderLineItemInput =
    FulfillmentOrderLineItemInput(id = "gid://shopify/FulfillmentOrderLineItem/$lineItemId", quantity = quantity)

  /** One shipment against an order with nothing planned before it; production goes through `dryRunAllShipments`. */
  private fun matchOneShipment(order: Order, shipment: Shipment): ShipmentMatchResult =
    matchShipmentToFulfillmentOrders(shipment, openFulfillmentLines(order), emptyList())
}
