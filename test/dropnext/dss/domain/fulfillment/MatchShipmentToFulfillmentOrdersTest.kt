package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.testutil.fixture.COMPLETE_PAGE
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.diagramCrossFoShipment
import dropnext.dss.testutil.fixture.foLineItemConnection
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.openFulfillmentOrder
import dropnext.dss.testutil.fixture.orderWithFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
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
    assert(result == matched(301L to lineInput(401L, 1)))
  }

  @Test
  fun `a variant whose only fulfillment order is closed has no room`() {
    val closed = FulfillmentOrder(
      id = "gid://shopify/FulfillmentOrder/302",
      status = FulfillmentOrderStatus.CLOSED,
      lineItems = foLineItemConnection(variantId = 101L, remaining = 2),
    )
    val order = orderWithFulfillmentOrders(closed)
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result == ShipmentMatchResult.Skipped(SkipReason.NO_ROOM, listOf(ShortLine(101L, asked = 1, available = 0))))
  }

  /** An over-shipment is normal: the shipment stays out of Shopify, whole, and nothing is refused. */
  @Test
  fun `a line asking more than is left skips the shipment whole as no room`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2))
    assert(result == ShipmentMatchResult.Skipped(SkipReason.NO_ROOM, listOf(ShortLine(101L, asked = 2, available = 1))))
  }

  /** A fulfillment is a whole package: the line that would fit is not filed on its own. */
  @Test
  fun `a line not on the order skips the whole shipment, the fitting line included`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(lines = listOf(101L to 1, 999L to 1)))
    assert(result == ShipmentMatchResult.Skipped(SkipReason.NOT_ON_ORDER, listOf(ShortLine(999L, asked = 1, available = 0))))
  }

  @Test
  fun `a shipment whose only variant is not on the order is skipped as not on the order`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(variantId = 999L, quantity = 1))
    assert((result as ShipmentMatchResult.Skipped).reason == SkipReason.NOT_ON_ORDER)
  }

  /** A variant the order does not have is a data problem; that it also lacks room elsewhere is the lesser news. */
  @Test
  fun `not on the order wins over no room, and every short line is named`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val result = matchOneShipment(order, shipment(lines = listOf(101L to 3, 999L to 1)))
    assert(
      result == ShipmentMatchResult.Skipped(
        SkipReason.NOT_ON_ORDER,
        listOf(ShortLine(101L, asked = 3, available = 2), ShortLine(999L, asked = 1, available = 0)),
      ),
    )
  }

  /** Every fulfillment-order line belongs to a line of the order, closed ones included. */
  @Test
  fun `the variants on the order are those of its line items`() {
    assert(variantIdsOnOrder(minimalOrder()) == setOf(101L))
  }

  @Test
  fun `a line item whose variant id cannot be read puts no variant on the order`() {
    val order = minimalOrder()
    val unreadable = order.lineItems.edges.single().let { edge ->
      edge.copy(node = edge.node.copy(variant = ProductVariant(legacyResourceId = "not-a-number")))
    }
    val withoutVariant = order.lineItems.edges.single().let { edge -> edge.copy(node = edge.node.copy(variant = null)) }
    val lineItems = order.lineItems.copy(edges = listOf(unreadable, withoutVariant))
    assert(variantIdsOnOrder(order.copy(lineItems = lineItems)).isEmpty())
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
    assert(result == matched(301L to lineInput(401L, 1)))
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
    val result = matchOneShipment(order, shipment) as ShipmentMatchResult.Matched
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

    val result = matchOneShipment(order, shipment)
    assert(result == matched(301L to lineInput(401L, 3)))
  }

  @Test
  fun `a variant with nothing remaining has no room`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 0))
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 1))
    assert(result == ShipmentMatchResult.Skipped(SkipReason.NO_ROOM, listOf(ShortLine(101L, asked = 1, available = 0))))
  }

  @Test
  fun `cross-shipment allocation within limits matches every shipment`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 3))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 2),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(result == listOf(matched(301L to lineInput(401L, 2)), matched(301L to lineInput(401L, 1))))
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
    assert(result == matched(302L to lineInput(402L, 1)))
  }

  @Test
  fun `a closed fulfillment order and an open one with nothing remaining leave no room`() {
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
    assert((result as ShipmentMatchResult.Skipped).reason == SkipReason.NO_ROOM)
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
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2)) as ShipmentMatchResult.Matched
    assert(result.groups.keys.single().endsWith("302"))
  }

  /** The request validators refuse a non-positive quantity; one that gets past them is a bug, not a skip. */
  @Test
  fun `a non-positive quantity is a bug the request validators keep out`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val thrown = runCatching { matchOneShipment(order, shipment(variantId = 101L, quantity = 0)) }.exceptionOrNull()
    assert(thrown is IllegalArgumentException)
  }

  @Test
  fun `a shipment without lines is a bug the request validators keep out`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val thrown = runCatching { matchOneShipment(order, shipment(lines = emptyList())) }.exceptionOrNull()
    assert(thrown is IllegalArgumentException)
  }

  @Test
  fun `each shipment of a payload is skipped on its own, with its own reason`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val shipments = listOf(
      shipment(tracking = "TRK-1", lines = listOf(101L to 3)),
      shipment(tracking = "TRK-2", variantId = 888L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(result.map { (it as ShipmentMatchResult.Skipped).reason } == listOf(SkipReason.NO_ROOM, SkipReason.NOT_ON_ORDER))
  }

  @Test
  fun `groups are keyed by the fulfillment order gid in first-seen order`() {
    val result = matchOneShipment(diagramCrossFoOrder(), diagramCrossFoShipment()) as ShipmentMatchResult.Matched
    assert(
      result.groups.keys.toList() == listOf(
        "gid://shopify/FulfillmentOrder/301",
        "gid://shopify/FulfillmentOrder/302"
      )
    )
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
      variantIdsOnOrder(order),
      planned,
    )
    assert(result == ShipmentMatchResult.Skipped(SkipReason.NO_ROOM, listOf(ShortLine(101L, asked = 2, available = 1))))
  }

  /** A re-ship entered before the job ran: the original takes the units, the re-ship stays out of Shopify. */
  @Test
  fun `a re-ship in the same payload as the original is skipped as no room`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 2))
    val shipments = listOf(
      shipment(tracking = "TRK-A", variantId = 101L, quantity = 2),
      shipment(tracking = "TRK-B", variantId = 101L, quantity = 2),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(result.first() == matched(301L to lineInput(401L, 2)))
    assert(result.last() == ShipmentMatchResult.Skipped(SkipReason.NO_ROOM, listOf(ShortLine(101L, asked = 2, available = 0))))
  }

  @Test
  fun `a unit an earlier shipment of the payload claimed leaves a later one without room`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
    val shipments = listOf(
      shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
      shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert((result.last() as ShipmentMatchResult.Skipped).reason == SkipReason.NO_ROOM)
  }

  @Test
  fun `a small shipment after a skipped big one still gets the room that is left`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
    val shipments = listOf(
      shipment(tracking = "TRK-B", variantId = 101L, quantity = 2),
      shipment(tracking = "TRK-C", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(result.first() is ShipmentMatchResult.Skipped)
    assert(result.last() == matched(301L to lineInput(401L, 1)))
  }

  /** The line that would have fit is not claimed by a shipment that is skipped for another line. */
  @Test
  fun `a skipped shipment claims nothing, not even the lines that fit`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
    val shipments = listOf(
      shipment(tracking = "TRK-B", lines = listOf(101L to 1, 999L to 1)),
      shipment(tracking = "TRK-C", variantId = 101L, quantity = 1),
    )
    val result = dryRunAllShipments(order, shipments)
    assert(result.last() == matched(301L to lineInput(401L, 1)))
  }

  // ---------- one variant, several places it could be filed ----------

  /**
   * One variant can sit on several open lines: two line items of the order, or one line routed to two locations. A
   * shipment line that no single one can serve is spread over them rather than skipped.
   */
  @Test
  fun `a quantity larger than any single open line is spread over the lines that cover it together`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 301L, lineItemId = 401L),
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 302L, lineItemId = 402L),
    )
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 2))
    assert(result == matched(301L to lineInput(401L, 1), 302L to lineInput(402L, 1)))
  }

  /** Taking the line with the most left first keeps a spread on as few lines as it can be. */
  @Test
  fun `a spread takes all of the line with the most left before the next, the first in Graphql order on a tie`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(variantId = 101L, remaining = 2, foId = 301L, lineItemId = 401L),
      openFulfillmentOrder(variantId = 101L, remaining = 3, foId = 302L, lineItemId = 402L),
      openFulfillmentOrder(variantId = 101L, remaining = 2, foId = 303L, lineItemId = 403L),
    )
    val ok = matchOneShipment(order, shipment(variantId = 101L, quantity = 4)) as ShipmentMatchResult.Matched
    assert(
      ok.groups.values.flatten().map { it.id to it.quantity } == listOf(
        "gid://shopify/FulfillmentOrderLineItem/402" to 3,
        "gid://shopify/FulfillmentOrderLineItem/401" to 1,
      ),
    )
  }

  @Test
  fun `a quantity above what the variant's open lines have left together is short by that total`() {
    val order = orderWithFulfillmentOrders(
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 301L, lineItemId = 401L),
      openFulfillmentOrder(variantId = 101L, remaining = 1, foId = 302L, lineItemId = 402L),
    )
    val result = matchOneShipment(order, shipment(variantId = 101L, quantity = 3))
    assert(result == ShipmentMatchResult.Skipped(SkipReason.NO_ROOM, listOf(ShortLine(101L, asked = 3, available = 2))))
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
    val result = dryRunAllShipments(order, shipments)
    assert(result.last() == ShipmentMatchResult.Skipped(SkipReason.NO_ROOM, listOf(ShortLine(101L, asked = 1, available = 0))))
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
    val result = matchOneShipment(orderWithFulfillmentOrders(fulfillmentOrder), shipment(variantId = 101L, quantity = 3))
    assert(result == matched(301L to lineInput(402L, 3)))
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
    val result = matchOneShipment(orderWithFulfillmentOrders(fulfillmentOrder), shipment(variantId = 999L, quantity = 1))
    assert((result as ShipmentMatchResult.Skipped).reason == SkipReason.NOT_ON_ORDER)
  }

  // ---------- a tracking number already on a live fulfillment ----------

  @Test
  fun `a shipment whose tracking number is on a live fulfillment is already fulfilled and plans nothing`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
      .copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val result = dryRunAllShipments(order, listOf(shipment(tracking = "TRK-A", variantId = 101L, quantity = 1)))
    val alreadyFulfilled = result.single() as ShipmentMatchResult.AlreadyFulfilled
    assert(alreadyFulfilled.fulfillments.map { it.id } == listOf("gid://shopify/Fulfillment/8000"))
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
    val result = dryRunAllShipments(order, shipments)
    assert(result.last() == matched(301L to lineInput(401L, 1)))
  }

  @Test
  fun `a shipment on a live fulfillment is already fulfilled rather than short of room`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
      .copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val result = dryRunAllShipments(order, listOf(shipment(tracking = "TRK-A", variantId = 101L, quantity = 2)))
    assert(result.single() is ShipmentMatchResult.AlreadyFulfilled)
  }

  @Test
  fun `a shipment whose tracking number is only on a canceled fulfillment is matched like a new one`() {
    val order = orderWithFulfillmentOrders(openFulfillmentOrder(variantId = 101L, remaining = 1))
      .copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"), status = FulfillmentStatus.CANCELLED)))
    val result = dryRunAllShipments(order, listOf(shipment(tracking = "TRK-A", variantId = 101L, quantity = 1)))
    assert(result.single() == matched(301L to lineInput(401L, 1)))
  }

  private fun lineInput(lineItemId: Long, quantity: Int): FulfillmentOrderLineItemInput =
    FulfillmentOrderLineItemInput(id = "gid://shopify/FulfillmentOrderLineItem/$lineItemId", quantity = quantity)

  /** A match whose groups are the given fulfillment order ids with one planned line each, in the order given. */
  private fun matched(vararg groups: Pair<Long, FulfillmentOrderLineItemInput>): ShipmentMatchResult.Matched =
    ShipmentMatchResult.Matched(groups.associate { (foId, input) -> "gid://shopify/FulfillmentOrder/$foId" to listOf(input) })

  /** One shipment against an order with nothing planned before it; production goes through `dryRunAllShipments`. */
  private fun matchOneShipment(order: Order, shipment: Shipment): ShipmentMatchResult =
    matchShipmentToFulfillmentOrders(shipment, openFulfillmentLines(order), variantIdsOnOrder(order), emptyList())
}
