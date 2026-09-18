package dropnext.dss.workflow

import dropnext.dss.contract.Shipment
import dropnext.dss.domain.fulfillment.ShortLine
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.getorderfordss.Order
import org.junit.jupiter.api.Test


class CalculateShopifyMutationsTest {

  /** The production function takes the aftermath of the run's cancels; most cases here cancel nothing. */
  private fun planFor(
    order: Order,
    shipments: List<Shipment>,
    aftermath: CancelAftermath = CancelAftermath.nothingCanceled(order),
  ) = calculateShopifyMutations(order, shipments, aftermath)

  @Test
  fun `plans a create when the order has no existing fulfillments`() {
    val mutations = planFor(minimalOrder(), listOf(shipment())).mutations
    assert(mutations.size == 1)
    val create = mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "1Z999")
    assert(create.carrier == "UPS")
    assert(create.lineItems.single().quantity == 1)
    assert(create.lineItems.single().fulfillmentOrderId == "gid://shopify/FulfillmentOrder/301")
    assert(create.lineItems.single().lineItemId == "gid://shopify/FulfillmentOrderLineItem/401")
  }

  /** One parcel is one fulfillment, however many fulfillment orders Shopify routed its lines to. */
  @Test
  fun `a shipment spanning two fulfillment orders plans one create carrying its tracking and every matched line`() {
    val parcel = shipment(tracking = "TRK-A", trackingUrl = "https://carrier.test/track/TRK-A", lines = listOf(1L to 1, 5L to 1))
    val create = planFor(diagramCrossFoOrder(), listOf(parcel)).mutations.single()
    assert(
      create == ShopifyMutation.FulfillmentCreate(
        lineItems = listOf(
          FulfillmentLine(
            fulfillmentOrderId = "gid://shopify/FulfillmentOrder/301",
            lineItemId = "gid://shopify/FulfillmentOrderLineItem/401",
            quantity = 1,
          ),
          FulfillmentLine(
            fulfillmentOrderId = "gid://shopify/FulfillmentOrder/302",
            lineItemId = "gid://shopify/FulfillmentOrderLineItem/402",
            quantity = 1,
          ),
        ),
        trackingNumber = "TRK-A",
        carrier = "UPS",
        trackingUrl = "https://carrier.test/track/TRK-A",
        notifyCustomer = true,
      ),
    )
  }

  // ---------- which creates notify the customer ----------

  /** Until DropNext sends its own emails, Shopify's shipping email is the customer's only word of a new package. */
  @Test
  fun `a create for a tracking number on no fulfillment of the order notifies the customer`() {
    val create = planFor(minimalOrder(), listOf(shipment())).mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.notifyCustomer)
  }

  /** The customer already had an email for this number: a re-created fulfillment must not send a second one. */
  @Test
  fun `a create for a tracking number only on a canceled fulfillment does not notify the customer`() {
    val order = minimalOrder().copy(fulfillments = listOf(fulfillment(8000L, listOf("1Z999"), status = FulfillmentStatus.CANCELLED)))
    val create = planFor(order, listOf(shipment())).mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(!create.notifyCustomer)
  }

  @Test
  fun `the notification is decided per shipment`() {
    val order = orderWithFoQuantities(remaining = 2).copy(
      fulfillments = listOf(fulfillment(8000L, listOf("TRK-SEEN"), status = FulfillmentStatus.CANCELLED)),
    )
    val plan = planFor(order, listOf(shipment(tracking = "TRK-NEW"), shipment(tracking = " TRK-SEEN ")))
    // The order has carried TRK-SEEN, so it is matched first; the notification is per shipment either way.
    val notified = plan.mutations
      .map { (it as ShopifyMutation.FulfillmentCreate).trackingNumber to it.notifyCustomer }
      .sortedBy { (trackingNumber, _) -> trackingNumber.trim() }
    assert(notified == listOf("TRK-NEW" to true, " TRK-SEEN " to false))
  }

  @Test
  fun `does not plan cancels when fulfillments already exist`() {
    val mutations = planFor(orderWithFulfillment(8000L), listOf(shipment())).mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    assert(mutations.single() is ShopifyMutation.FulfillmentCreate)
  }

  /** An over-shipment is a normal outcome: the shipment is left out, and the plan is not refused. */
  @Test
  fun `a shipment asking more than is left is skipped as no room and plans no create`() {
    val plan = planFor(minimalOrder(), listOf(shipment(quantity = 99)))
    assert(plan.mutations.isEmpty())
    assert(plan.skippedShipments.single().reason == SkipReason.NO_ROOM)
    assert(plan.skippedShipments.single().shortLines == listOf(ShortLine(101L, asked = 99, available = 2)))
  }

  @Test
  fun `a shipment whose variant is not on the order is skipped as not on the order`() {
    val plan = planFor(minimalOrder(), listOf(shipment(variantId = 999L)))
    assert(plan.mutations.isEmpty())
    assert(plan.skippedShipments.single().reason == SkipReason.NOT_ON_ORDER)
  }

  @Test
  fun `a later shipment the earlier ones leave no room for is skipped while the earlier ones are planned`() {
    val shipments = listOf(
      shipment(tracking = "TRK-1", quantity = 2),
      shipment(tracking = "TRK-2", quantity = 1),
    )
    val plan = planFor(minimalOrder(), shipments)
    assert(plan.mutations.map { (it as ShopifyMutation.FulfillmentCreate).trackingNumber } == listOf("TRK-1"))
    assert(plan.skippedShipments.map { it.shipment.trackingNumber } == listOf("TRK-2"))
  }

  /** The first payload of the spec: one unit left after an earlier fulfillment, and a shipment of two. */
  @Test
  fun `an over-shipment after an earlier fulfillment is skipped, not clipped`() {
    val order = orderWithFoQuantities(remaining = 1).copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val plan = planFor(order, listOf(shipment(tracking = "TRK-B", quantity = 2)))
    assert(plan.mutations.isEmpty())
    assert(plan.skippedShipments.single().shortLines == listOf(ShortLine(101L, asked = 2, available = 1)))
  }

  @Test
  fun `second variant shipment does not plan cancel of the first fulfillment`() {
    val order = orderWithTwoVariantFulfillmentOrders(
      firstVariantId = 101L,
      firstRemaining = 0,
      secondVariantId = 202L,
      secondRemaining = 1,
    ).copy(
      fulfillments = listOf(
        fulfillment(8000L),
      ),
    )
    val mutations = planFor(order, listOf(shipment(variantId = 202L, tracking = "TRK-O2"))).mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    val create = mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "TRK-O2")
    assert(create.lineItems.single().fulfillmentOrderId == "gid://shopify/FulfillmentOrder/302")
  }

  @Test
  fun `same variant remaining quantity plans a create without cancel`() {
    val mutations = planFor(orderWithFoQuantities(remaining = 2), listOf(shipment(quantity = 1))).mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    val create = mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.lineItems.single().quantity == 1)
  }

  @Test
  fun `already fulfilled variant is omitted and does not plan cancel`() {
    val order = orderWithFoQuantities(remaining = 0).copy(
      fulfillments = listOf(
        fulfillment(8000L),
      ),
    )
    val plan = planFor(order, listOf(shipment(quantity = 1)))
    assert(plan.mutations.isEmpty())
  }

  @Test
  fun `mixed already-fulfilled and open variants creates only the open one`() {
    val order = orderWithTwoVariantFulfillmentOrders(
      firstVariantId = 101L,
      firstRemaining = 0,
      secondVariantId = 202L,
      secondRemaining = 1,
    ).copy(
      fulfillments = listOf(
        fulfillment(8000L),
      ),
    )
    val shipments = listOf(
      shipment(variantId = 101L, tracking = "TRK-H"),
      shipment(variantId = 202L, tracking = "TRK-O"),
    )
    val mutations = planFor(order, shipments).mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    val create = mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "TRK-O")
  }

  @Test
  fun `the plan carries the skipped shipments in payload order with their reasons`() {
    val order = orderWithTwoVariantFulfillmentOrders(
      firstVariantId = 101L,
      firstRemaining = 0,
      secondVariantId = 202L,
      secondRemaining = 1,
    )
    val shipments = listOf(
      shipment(variantId = 101L, tracking = "TRK-H"),
      shipment(variantId = 999L, tracking = "TRK-U"),
      shipment(variantId = 202L, tracking = "TRK-O"),
    )
    val plan = planFor(order, shipments)
    assert(plan.mutations.size == 1)
    assert(
      plan.skippedShipments.map { it.shipment.trackingNumber to it.reason } ==
        listOf("TRK-H" to SkipReason.NO_ROOM, "TRK-U" to SkipReason.NOT_ON_ORDER),
    )
  }

  @Test
  fun `a fully matched payload plans no skips`() {
    val plan = planFor(minimalOrder(), listOf(shipment()))
    assert(plan.skippedShipments.isEmpty())
    assert(plan.alreadyFulfilledShipments.isEmpty())
  }

  /** Two units of a variant, the first shipped as TRK-A, and the monolith sending `[A, B]` again after a partial failure. */
  @Test
  fun `a re-sent payload plans a create only for the shipment not yet fulfilled`() {
    val order = orderWithFoQuantities(remaining = 1).copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val plan = planFor(order, listOf(shipment(tracking = "TRK-A"), shipment(tracking = "TRK-B")))
    val create = plan.mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "TRK-B")
    assert(plan.alreadyFulfilledShipments.single().shipment.trackingNumber == "TRK-A")
    assert(plan.alreadyFulfilledShipments.single().fulfillments.single().id == "gid://shopify/Fulfillment/8000")
    assert(plan.skippedShipments.isEmpty())
  }

  @Test
  fun `a payload whose shipments are all fulfilled plans nothing`() {
    val order = orderWithFoQuantities(remaining = 1).copy(
      fulfillments = listOf(fulfillment(8000L, listOf("TRK-A")), fulfillment(8001L, listOf("TRK-B"))),
    )
    val plan = planFor(order, listOf(shipment(tracking = "TRK-A"), shipment(tracking = "TRK-B")))
    assert(plan.mutations.isEmpty())
    assert(plan.alreadyFulfilledShipments.map { it.shipment.trackingNumber } == listOf("TRK-A", "TRK-B"))
    assert(plan.skippedShipments.isEmpty())
  }

  // ---------- after the run's cancels ----------

  /** F1 was canceled, and the reload still shows it live: B must be created, not counted as fulfilled by F1. */
  @Test
  fun `a fulfillment this run canceled does not count as fulfilling its number`() {
    val staleF1 = fulfillment(8000L, listOf("TRK-A"))
    val order = orderWithFoQuantities(remaining = 1).copy(fulfillments = listOf(staleF1))
    val aftermath = CancelAftermath.nothingCanceled(order).copy(canceledFulfillmentIds = setOf(staleF1.id), orderChanged = true)

    val plan = planFor(order, listOf(shipment(tracking = "TRK-A")), aftermath)

    assert((plan.mutations.single() as ShopifyMutation.FulfillmentCreate).trackingNumber == "TRK-A")
    assert(plan.alreadyFulfilledShipments.isEmpty())
  }

  /** A stale fulfillment is still live, so a create would put its number on a second one. */
  @Test
  fun `a shipment whose number is on a blocking fulfillment is blocked, not planned`() {
    val stale = fulfillment(8000L, listOf(" TRK-A"))
    val order = orderWithFoQuantities(remaining = 2).copy(fulfillments = listOf(stale))
    val aftermath = CancelAftermath.nothingCanceled(order).copy(blockingFulfillments = listOf(stale))

    val plan = planFor(order, listOf(shipment(tracking = "TRK-A "), shipment(tracking = "TRK-B")), aftermath)

    assert(plan.blockedShipments.map { it.shipment.trackingNumber to it.fulfillment.id } == listOf("TRK-A " to stale.id))
    assert(plan.alreadyFulfilledShipments.isEmpty())
    assert(plan.mutations.map { (it as ShopifyMutation.FulfillmentCreate).trackingNumber } == listOf("TRK-B"))
  }

  /**
   * Shopify has carried TRK-B's package under its number, so the customer is tracking it; an older shipment that never
   * fit must not take the room its canceled fulfillment gave back.
   */
  @Test
  fun `a shipment the order has carried is matched before the shipments ahead of it in the request`() {
    val before = orderWithFoQuantities(remaining = 0).copy(
      fulfillments = listOf(fulfillment(8000L, listOf("TRK-B"), status = FulfillmentStatus.CANCELLED)),
    )
    val after = orderWithFoQuantities(remaining = 1)
    val aftermath = CancelAftermath.nothingCanceled(before).copy(orderChanged = true)

    val plan = planFor(after, listOf(shipment(tracking = "TRK-A"), shipment(tracking = " TRK-B")), aftermath)

    assert(plan.mutations.map { (it as ShopifyMutation.FulfillmentCreate).trackingNumber } == listOf(" TRK-B"))
    assert(plan.skippedShipments.map { it.shipment.trackingNumber } == listOf("TRK-A"))
  }

  /** The reload shows F1 canceled either way; what the customer was mailed about is what the order carried before. */
  @Test
  fun `whether a create notifies is decided against the order before the cancels`() {
    val before = orderWithFoQuantities(remaining = 0).copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val after = orderWithFoQuantities(remaining = 2)
    val aftermath = CancelAftermath.nothingCanceled(before).copy(canceledFulfillmentIds = setOf("gid://shopify/Fulfillment/8000"), orderChanged = true)

    val plan = planFor(after, listOf(shipment(tracking = "TRK-A"), shipment(tracking = "TRK-C")), aftermath)

    assert(plan.mutations.map { (it as ShopifyMutation.FulfillmentCreate).let { create -> create.trackingNumber to create.notifyCustomer } } ==
      listOf("TRK-A" to false, "TRK-C" to true))
  }
}
