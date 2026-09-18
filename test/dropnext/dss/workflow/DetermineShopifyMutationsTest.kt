package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.MutableTimeSource
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.successValue
import dropnext.graphql.generated.getorderfordss.Order
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


class DetermineShopifyMutationsTest {

  private val unlimited = ShipmentSyncBudget.unlimited()

  private fun untouched(order: Order) = CancelAftermath.nothingCanceled(order)

  private fun changed(order: Order) = CancelAftermath.nothingCanceled(order).copy(orderChanged = true)

  @Test
  fun `without a cancel the order loaded before is planned against, and nothing is sent`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val plan = determineShopifyMutations(fake, ShopifyOrderId(1001L), listOf(shipment()), untouched(minimalOrder()), ShipmentSyncBudget.unlimited()).successValue()
    assert(plan.mutations.size == 1)
    assert(fake.orderForDssCalls.isEmpty())
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  /** Shopify puts canceled units on fulfillment orders the first load may not have had. */
  @Test
  fun `after a cancel the order is loaded again and planned against`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(orderWithFoQuantities(remaining = 2)) }
    val before = orderWithFoQuantities(remaining = 0)

    val plan = determineShopifyMutations(fake, ShopifyOrderId(1001L), listOf(shipment(quantity = 2)), changed(before), ShipmentSyncBudget.unlimited()).successValue()

    assert(fake.orderForDssCalls == listOf("gid://shopify/Order/1001"))
    assert(plan.mutations.size == 1)
    assert(plan.skippedShipments.isEmpty())
  }

  /** The error is the service's, already triaged: the plan step adds nothing to it and sends nothing after it. */
  @Test
  fun `a failed reload is passed through and nothing is planned`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.NotFound("order 1001 not found"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), listOf(shipment()), changed(minimalOrder()), unlimited)
    assert(result == Failure(ShopifyError.NotFound("order 1001 not found")))
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  fun `a reload the budget would not hold is not started`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val clock = MutableTimeSource()
    val budget = ShipmentSyncBudget.startedAt(clock.markNow(), 25.seconds)
    clock += 16.seconds

    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), listOf(shipment()), changed(minimalOrder()), budget)

    assert(result == Failure(ShopifyError.TimedOut("syncing the shipments", 25.seconds)))
    assert(fake.orderForDssCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `logs one line per skipped shipment in payload order, naming its reason and short lines`() {
    val order = orderWithTwoVariantFulfillmentOrders(
      firstVariantId = 101L,
      firstRemaining = 0,
      secondVariantId = 202L,
      secondRemaining = 1,
    )
    val shipments = listOf(
      shipment(variantId = 101L, tracking = "TRK-H"),
      shipment(tracking = "TRK-U", lines = listOf(202L to 1, 999L to 2)),
      shipment(variantId = 202L, tracking = "TRK-O"),
    )
    val lines = capturingLogs {
      runBlocking {
        determineShopifyMutations(FakeShopifyGraphqlService(), ShopifyOrderId(1001L), shipments, untouched(order), unlimited)
      }
    }
    val skipLines = lines.filter { "sync-shipments skipped " in it }
    assert(skipLines.size == 2)
    assert(skipLines[0].startsWith("INFO sync-shipments skipped shipment orderId=1001 tracking=TRK-H reason=no_room short=[variant=101:asked=1:available=0]"))
    assert(skipLines[1].startsWith("WARN sync-shipments skipped shipment orderId=1001 tracking=TRK-U reason=not_on_order short=[variant=999:asked=2:available=0]"))
  }

  /** An over-shipment is expected; the line says how far over it was. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a shipment without room is logged at info with what it asked and what was left`() {
    val lines = capturingLogs {
      runBlocking {
        determineShopifyMutations(FakeShopifyGraphqlService(), ShopifyOrderId(1001L), listOf(shipment(quantity = 99)), untouched(minimalOrder()), unlimited)
      }
    }
    val line = lines.single { "sync-shipments skipped " in it }
    assert(line.startsWith("INFO sync-shipments skipped shipment orderId=1001 tracking=1Z999 reason=no_room short=[variant=101:asked=99:available=2]"))
  }

  /** A re-send is how the monolith recovers from a lost commit or a `502`, so it is information, not a warning. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a shipment already fulfilled is logged at info with the fulfillment it landed on`() {
    val order = orderWithFoQuantities(remaining = 1).copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A"))))
    val lines = capturingLogs {
      runBlocking {
        determineShopifyMutations(FakeShopifyGraphqlService(), ShopifyOrderId(1001L), listOf(shipment(tracking = "TRK-A")), untouched(order), unlimited)
      }
    }
    val line = lines.single { "sync-shipments skipped " in it }
    assert(line.startsWith("INFO sync-shipments skipped shipment orderId=1001 tracking=TRK-A reason=already_fulfilled fulfillmentIds=[8000]"))
  }

  /** Put there by hand, or a duplicate from before the skip existed: the sync leaves it alone, and a human cleans it up. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a tracking number on two live fulfillments is logged at warn with both`() {
    val order = orderWithFoQuantities(remaining = 1).copy(
      fulfillments = listOf(fulfillment(8000L, listOf("TRK-A")), fulfillment(8001L, listOf("TRK-A"))),
    )
    val lines = capturingLogs {
      runBlocking {
        determineShopifyMutations(FakeShopifyGraphqlService(), ShopifyOrderId(1001L), listOf(shipment(tracking = "TRK-A")), untouched(order), unlimited)
      }
    }
    val line = lines.single { "sync-shipments skipped " in it }
    assert(line.startsWith("WARN sync-shipments skipped shipment orderId=1001 tracking=TRK-A reason=already_fulfilled fulfillmentIds=[8000, 8001]"))
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a blocked shipment is logged at warn with the stale fulfillment in its way`() {
    val stale = fulfillment(8000L, listOf("TRK-A"))
    val order = orderWithFoQuantities(remaining = 1).copy(fulfillments = listOf(stale))
    val aftermath = untouched(order).copy(blockingFulfillments = listOf(stale))
    val lines = capturingLogs {
      runBlocking {
        determineShopifyMutations(FakeShopifyGraphqlService(), ShopifyOrderId(1001L), listOf(shipment(tracking = "TRK-A")), aftermath, unlimited)
      }
    }
    val line = lines.single { "sync-shipments skipped " in it }
    assert(line.startsWith("WARN sync-shipments skipped shipment orderId=1001 tracking=TRK-A reason=blocked fulfillmentId=8000"))
  }
}
