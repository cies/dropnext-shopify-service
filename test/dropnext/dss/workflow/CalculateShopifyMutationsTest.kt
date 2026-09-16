package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import org.junit.jupiter.api.Test


class CalculateShopifyMutationsTest {

  @Test
  fun `plans a create when the order has no existing fulfillments`() {
    val result = calculateShopifyMutations(minimalOrder(), listOf(shipment()))
    assert(result is Success)
    val mutations = result.successValue().mutations
    assert(mutations.size == 1)
    val create = mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "1Z999")
    assert(create.carrier == "UPS")
    assert(create.notifyCustomer == false)
    assert(create.lineItems.single().quantity == 1)
    assert(create.lineItems.single().fulfillmentOrderId == "gid://shopify/FulfillmentOrder/301")
    assert(create.lineItems.single().lineItemId == "gid://shopify/FulfillmentOrderLineItem/401")
  }

  /** One parcel is one fulfillment, however many fulfillment orders Shopify routed its lines to. */
  @Test
  fun `a shipment spanning two fulfillment orders plans one create carrying its tracking and every matched line`() {
    val parcel = shipment(tracking = "TRK-A", trackingUrl = "https://carrier.test/track/TRK-A", lines = listOf(1L to 1, 5L to 1))
    val create = calculateShopifyMutations(diagramCrossFoOrder(), listOf(parcel)).successValue().mutations.single()
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
        notifyCustomer = false,
      ),
    )
  }

  @Test
  fun `does not plan cancels when fulfillments already exist`() {
    val order = orderWithFulfillment(8000L)
    val result = calculateShopifyMutations(order, listOf(shipment()))
    val mutations = result.successValue().mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    assert(mutations.single() is ShopifyMutation.FulfillmentCreate)
  }

  @Test
  fun `quantity exceed is a failure with no mutation list`() {
    val result = calculateShopifyMutations(minimalOrder(), listOf(shipment(quantity = 99)))
    assert(result is Failure)
    val error = result.failureReason()
    assert(error is ShopifyError.UserError)
  }

  @Test
  fun `unmatched shipment is omitted from the mutation list`() {
    val result = calculateShopifyMutations(minimalOrder(), listOf(shipment(variantId = 999L)))
    assert(result is Success)
    assert(result.successValue().mutations.isEmpty())
  }

  @Test
  fun `cross-shipment over-allocation fails before any create is in the list`() {
    val shipments = listOf(
      shipment(tracking = "TRK-1", quantity = 2),
      shipment(tracking = "TRK-2", quantity = 1),
    )
    val result = calculateShopifyMutations(minimalOrder(), shipments)
    assert(result is Failure)
    assert(result.failureReason() is ShopifyError.UserError)
  }

  @Test
  fun `payload that exceeds remaining after existing fulfillment fails with no cancels`() {
    val order = orderWithFoQuantities(remaining = 1).copy(
      fulfillments = listOf(
        fulfillment(8000L),
      ),
    )
    val shipments = listOf(
      shipment(tracking = "TRK-1", quantity = 1),
      shipment(tracking = "TRK-2", quantity = 1),
    )
    val result = calculateShopifyMutations(order, shipments)
    assert(result is Failure)
    assert(result.failureReason() is ShopifyError.UserError)
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
    val result = calculateShopifyMutations(order, listOf(shipment(variantId = 202L, tracking = "TRK-O2")))
    val mutations = result.successValue().mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    val create = mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "TRK-O2")
    assert(create.lineItems.single().fulfillmentOrderId == "gid://shopify/FulfillmentOrder/302")
  }

  @Test
  fun `same variant remaining quantity plans a create without cancel`() {
    val order = orderWithFoQuantities(remaining = 2)
    val result = calculateShopifyMutations(order, listOf(shipment(quantity = 1)))
    val mutations = result.successValue().mutations
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
    val result = calculateShopifyMutations(order, listOf(shipment(quantity = 1)))
    assert(result is Success)
    assert(result.successValue().mutations.isEmpty())
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
    val result = calculateShopifyMutations(order, shipments)
    val mutations = result.successValue().mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    val create = mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "TRK-O")
  }

  @Test
  fun `the plan carries the skipped lines and the shipments that matched nothing`() {
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
    val plan = calculateShopifyMutations(order, shipments).successValue()
    assert(plan.mutations.size == 1)
    assert(plan.skippedLines.map { it.trackingNumber to it.reason } == listOf("TRK-H" to SkipReason.ZERO_REMAINING, "TRK-U" to SkipReason.NO_OPEN_FO))
    assert(plan.unmatchedShipments.map { it.trackingNumber } == listOf("TRK-H", "TRK-U"))
  }

  @Test
  fun `a fully matched payload plans no skips`() {
    val plan = calculateShopifyMutations(minimalOrder(), listOf(shipment())).successValue()
    assert(plan.skippedLines.isEmpty())
    assert(plan.unmatchedShipments.isEmpty())
  }

  /** Two units of a variant, the first shipped as TRK-A, and the monolith sending `[A, B]` again after a partial failure. */
  @Test
  fun `a re-sent payload plans a create only for the shipment not yet fulfilled`() {
    val order = orderWithFoQuantities(remaining = 1).copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val plan = calculateShopifyMutations(order, listOf(shipment(tracking = "TRK-A"), shipment(tracking = "TRK-B"))).successValue()
    val create = plan.mutations.single() as ShopifyMutation.FulfillmentCreate
    assert(create.trackingNumber == "TRK-B")
    assert(plan.alreadyFulfilledShipments.single().shipment.trackingNumber == "TRK-A")
    assert(plan.alreadyFulfilledShipments.single().fulfillments.single().id == "gid://shopify/Fulfillment/8000")
    assert(plan.unmatchedShipments.isEmpty())
  }

  @Test
  fun `a payload whose shipments are all fulfilled plans nothing`() {
    val order = orderWithFoQuantities(remaining = 1).copy(
      fulfillments = listOf(fulfillment(8000L, listOf("TRK-A")), fulfillment(8001L, listOf("TRK-B"))),
    )
    val plan = calculateShopifyMutations(order, listOf(shipment(tracking = "TRK-A"), shipment(tracking = "TRK-B"))).successValue()
    assert(plan.mutations.isEmpty())
    assert(plan.alreadyFulfilledShipments.map { it.shipment.trackingNumber } == listOf("TRK-A", "TRK-B"))
    assert(plan.skippedLines.isEmpty())
    assert(plan.unmatchedShipments.isEmpty())
  }
}
