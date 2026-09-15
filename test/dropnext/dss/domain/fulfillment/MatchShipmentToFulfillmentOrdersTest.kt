package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.diagramCrossFoShipment
import dropnext.dss.testutil.fixture.openFulfillmentOrder
import dropnext.dss.testutil.fixture.orderWithFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.getorderfordss.FulfillmentOrder
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItem
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemConnection
import dropnext.graphql.generated.getorderfordss.FulfillmentOrderLineItemEdge
import dropnext.graphql.generated.getorderfordss.Order
import dropnext.graphql.generated.getorderfordss.ProductVariant
import dropnext.graphql.generated.inputs.FulfillmentOrderLineItemInput
import kotlin.test.Test

class MatchShipmentToFulfillmentOrdersTest {

  @Test
  fun `matches open fulfillment order line items`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
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
      lineItems = foLineItems(variantId = 101L, remaining = 2),
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
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 1))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `partial match skips unknown variant`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val shipment = Shipment(
      trackingNumber = "1Z999",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(
        ShipmentLineItem(productVariantId = 101L, quantity = 1),
        ShipmentLineItem(productVariantId = 999L, quantity = 1),
      ),
    )
    val result = matchOneShipment(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 1)
    assert(result.groups.values.single().single().quantity == 1)
    assert(result.skipped.size == 1)
    assert(result.skipped.single().productVariantId == 999L)
    assert(result.skipped.single().reason == SkipReason.NO_OPEN_FO)
  }

  @Test
  fun `all lines skipped returns empty groups`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
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
      lineItems = foLineItems(variantId = 101L, remaining = 2),
    )
    val order = orderWithFulfillmentOrders(fo)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
  }

  @Test
  fun `groups line items across multiple fulfillment orders`() {
    val fo1 = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(variantId = 101L, remaining = 5),
    )
    val fo2 = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(variantId = 202L, remaining = 5),
    )
    val order = orderWithFulfillmentOrders(fo1, fo2)
    val shipment = Shipment(
      trackingNumber = "TRK",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(
        ShipmentLineItem(productVariantId = 101L, quantity = 2),
        ShipmentLineItem(productVariantId = 202L, quantity = 1),
      ),
    )
    val result = matchOneShipment(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 2)
    assert(result.groups.entries.single { it.key.endsWith("301") }.value.single().quantity == 2)
    assert(result.groups.entries.single { it.key.endsWith("302") }.value.single().quantity == 1)
  }

  @Test
  fun `diagram cross-FO shipment`() {
    val fo1 = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(lineItemId = 401L, variantId = 1L, remaining = 5),
    )
    val fo2 = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(lineItemId = 402L, variantId = 5L, remaining = 5),
    )
    val order = orderWithFulfillmentOrders(fo1, fo2)
    val shipment = Shipment(
      trackingNumber = "TRK-A",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(
        ShipmentLineItem(productVariantId = 1L, quantity = 1),
        ShipmentLineItem(productVariantId = 5L, quantity = 1),
      ),
    )
    val result = matchOneShipment(order, shipment) as ShipmentMatchResult.Ok
    assert(result.groups.size == 2)
    assert(result.skipped.isEmpty())
  }

  @Test
  fun `duplicate variant rows aggregated`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 5))
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
  fun `cross-shipment over-allocation fails dry-run`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 2),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(result is DryRunResult.UserError)
  }

  @Test
  fun `cross-shipment over-allocation vs remainingQuantity fails dry-run`() {
    // remaining already reduced; payload must not use post-cancel totalQuantity
    val order =
      orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 1, total = 2))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(result is DryRunResult.UserError)
  }

  @Test
  fun `two qty-1 shipments match when remaining is 2`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2, total = 2))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.Ok
    assert(result.perShipment.size == 2)
    assert(result.perShipment.sumOf { it.skipped.size } == 0)
    assert(result.perShipment.all { it.groups.isNotEmpty() })
  }

  @Test
  fun `zero remainingQuantity skips as ZERO_REMAINING`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 0, total = 1))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.isEmpty())
    assert(ok.skipped.single().reason == SkipReason.ZERO_REMAINING)
  }

  @Test
  fun `cross-shipment allocation within limits succeeds dry-run`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 3))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 2),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.Ok
    assert(result.perShipment.size == 2)
    assert(result.perShipment.sumOf { it.skipped.size } == 0)
  }

  @Test
  fun `null variant legacyResourceId skipped safely`() {
    val badLine =
      FulfillmentOrderLineItem(
        id = "gid://shopify/FulfillmentOrderLineItem/401",
        remainingQuantity = 5,
        totalQuantity = 5,
        variant = ProductVariant(
          id = "gid://shopify/ProductVariant/101",
          legacyResourceId = "not-a-number",
        ),
      )
    val goodFo =
      FulfillmentOrder(
        id = "gid://shopify/FulfillmentOrder/301",
        status = FulfillmentOrderStatus.OPEN,
        lineItems = FulfillmentOrderLineItemConnection(
          edges = listOf(FulfillmentOrderLineItemEdge(node = badLine)),
        ),
      )
    val goodFo2 = openFo(variantId = 101L, remaining = 5, foId = 302L, lineItemId = 402L)
    val order = orderWithFulfillmentOrders(goodFo, goodFo2)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result is ShipmentMatchResult.Ok)
  }

  @Test
  fun `closed FO with zero remaining skipped`() {
    val closedFo = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.CLOSED,
      lineItems = foLineItems(variantId = 101L, remaining = 0),
    )
    val openFoZero = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(lineItemId = 402L, variantId = 101L, remaining = 0),
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
      lineItems = foLineItems(lineItemId = 401L, variantId = 101L, remaining = 1),
    )
    val foHigh = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = foLineItems(lineItemId = 402L, variantId = 101L, remaining = 5),
    )
    val order = orderWithFulfillmentOrders(foLow, foHigh)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2))
    val ok = result as ShipmentMatchResult.Ok
    assert(ok.groups.keys.single().endsWith("302"))
  }

  @Test
  fun `zero quantity (bypassed validation) returns user error`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 0))
    assert(result is ShipmentMatchResult.UserError)
  }

  @Test
  fun `skipped line includes variant id and tracking number`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(variantId = 999L, quantity = 1))
    val ok = result as ShipmentMatchResult.Ok
    val skipped = ok.skipped.single()
    assert(skipped.productVariantId == 999L)
    assert(skipped.trackingNumber == "1Z999")
  }

  @Test
  fun `dry-run reports total skipped lines across shipments`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
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
  }

  @Test
  fun `a unit already planned on the line counts against what a later shipment may take`() {
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 2))
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
    val order = orderWithFulfillmentOrders(openFo(variantId = 101L, remaining = 1, total = 2))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments) as DryRunResult.UserError
    assert("variant 101 requested quantity 1 exceeds remaining 0" in result.messages.single())
  }

  // ---------- one variant, several places it could be filed ----------

  /**
   * Pinned rather than chosen: a shipment line is filed under one fulfillment order and never
   * split, so two open lines of one unit each cannot serve a two-unit line. A multi-location shop
   * that spreads a variant over locations gets a 400 here and has to split the shipment line.
   */
  @Test
  fun `a quantity larger than any single open fulfillment order line is refused even when two lines could cover it together`() {
    val order = orderWithFulfillmentOrders(
      openFo(variantId = 101L, remaining = 1, foId = 301L, lineItemId = 401L),
      openFo(variantId = 101L, remaining = 1, foId = 302L, lineItemId = 402L),
    )
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2))
    assert(result is ShipmentMatchResult.UserError)
    assert("requested quantity 2 exceeds remaining 1" in (result as ShipmentMatchResult.UserError).messages.single())
  }

  @Test
  fun `the same variant on two lines of one open fulfillment order takes the line with more remaining`() {
    val variant = ProductVariant(id = "gid://shopify/ProductVariant/101", legacyResourceId = "101")
    val fulfillmentOrder = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = FulfillmentOrderLineItemConnection(
        edges = listOf(
          FulfillmentOrderLineItemEdge(
            node = FulfillmentOrderLineItem(
              id = "gid://shopify/FulfillmentOrderLineItem/401",
              remainingQuantity = 1,
              totalQuantity = 1,
              variant = variant
            )
          ),
          FulfillmentOrderLineItemEdge(
            node = FulfillmentOrderLineItem(
              id = "gid://shopify/FulfillmentOrderLineItem/402",
              remainingQuantity = 4,
              totalQuantity = 4,
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
      totalQuantity = 5,
      variant = ProductVariant(
        id = "gid://shopify/ProductVariant/101",
        legacyResourceId = "not-a-number"
      ),
    )
    val fulfillmentOrder = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/301",
      status = FulfillmentOrderStatus.OPEN,
      lineItems = FulfillmentOrderLineItemConnection(
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

  private fun openFo(
    variantId: Long,
    remaining: Int,
    total: Int = remaining,
    foId: Long = 301L,
    lineItemId: Long = 401L,
  ): FulfillmentOrder = openFulfillmentOrder(
    foId = foId,
    lineItemId = lineItemId,
    variantId = variantId,
    remaining = remaining,
    total = total
  )

  private fun foLineItems(
    variantId: Long,
    remaining: Int,
    total: Int = remaining,
  ): FulfillmentOrderLineItemConnection = foLineItems(401L, variantId, remaining, total)

  private fun foLineItems(
    lineItemId: Long,
    variantId: Long,
    remaining: Int,
    total: Int = remaining,
  ): FulfillmentOrderLineItemConnection = foLineItems(
    lineItemId,
    ProductVariant(
      id = "gid://shopify/ProductVariant/$variantId",
      legacyResourceId = variantId.toString(),
    ),
    remaining,
    total,
  )

  private fun foLineItems(
    lineItemId: Long,
    variant: ProductVariant,
    remaining: Int,
    total: Int = remaining,
  ) = FulfillmentOrderLineItemConnection(
    edges = listOf(
      FulfillmentOrderLineItemEdge(
        node = FulfillmentOrderLineItem(
          id = "gid://shopify/FulfillmentOrderLineItem/$lineItemId",
          remainingQuantity = remaining,
          totalQuantity = total,
          variant = variant,
        ),
      ),
    ),
  )

  /** One shipment against an order with nothing planned before it; production goes through `dryRunAllShipments`. */
  private fun matchOneShipment(order: Order, shipment: Shipment): ShipmentMatchResult =
    matchShipmentToFulfillmentOrders(shipment, openFulfillmentLines(order), emptyList())
}
