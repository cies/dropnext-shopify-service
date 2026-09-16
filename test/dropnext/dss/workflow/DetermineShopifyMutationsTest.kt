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
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


class DetermineShopifyMutationsTest {

  @Test
  fun `loads the order once and does not cancel or create`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(minimalOrder())
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Success)
    assert(fake.orderForDssCalls == listOf("gid://shopify/Order/1001"))
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  /** The error is the service's, already triaged: the plan step adds nothing to it and sends nothing after it. */
  @Test
  fun `a failed order load is passed through and nothing is planned`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.NotFound("order 1001 not found"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result == Failure(ShopifyError.NotFound("order 1001 not found")))
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `logs every skipped line and every unmatched shipment from the plan`() {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(
      orderWithTwoVariantFulfillmentOrders(
        firstVariantId = 101L,
        firstRemaining = 0,
        secondVariantId = 202L,
        secondRemaining = 1,
      ),
    )
    val shipments = listOf(
      shipment(variantId = 101L, tracking = "TRK-H"),
      shipment(variantId = 999L, tracking = "TRK-U", quantity = 2),
      shipment(variantId = 202L, tracking = "TRK-O"),
    )
    val lines = capturingLogs {
      runBlocking { determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments) }
    }
    val skipLines = lines.filter { "sync-shipments skipped " in it }
    assert(skipLines.size == 4)
    assert(skipLines[0].startsWith("WARN sync-shipments skipped line orderId=1001 tracking=TRK-H variant=101 reason=zero_remaining qty=1"))
    assert(skipLines[1].startsWith("WARN sync-shipments skipped shipment orderId=1001 tracking=TRK-H reason=all_lines_unmatched"))
    assert(skipLines[2].startsWith("WARN sync-shipments skipped line orderId=1001 tracking=TRK-U variant=999 reason=no_open_fo qty=2"))
    assert(skipLines[3].startsWith("WARN sync-shipments skipped shipment orderId=1001 tracking=TRK-U reason=all_lines_unmatched"))
    assert(fake.orderForDssCalls.size == 1)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a refused plan logs no skips`() {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(minimalOrder())
    val lines = capturingLogs {
      runBlocking { determineShopifyMutations(fake, ShopifyOrderId(1001L), listOf(shipment(quantity = 99))) }
    }
    assert(lines.none { "sync-shipments skipped " in it })
  }

  /** A re-send is how the monolith recovers from a lost commit or a `502`, so it is information, not a warning. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a shipment already fulfilled is logged at info with the fulfillment it landed on`() {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(
      orderWithFoQuantities(remaining = 1).copy(fulfillments = listOf(fulfillment(8000L, listOf("TRK-A")))),
    )
    val lines = capturingLogs {
      runBlocking { determineShopifyMutations(fake, ShopifyOrderId(1001L), listOf(shipment(tracking = "TRK-A"))) }
    }
    val line = lines.single { "sync-shipments skipped " in it }
    assert(line.startsWith("INFO sync-shipments skipped shipment orderId=1001 tracking=TRK-A reason=already_fulfilled fulfillmentIds=[8000]"))
  }

  /** Put there by hand, or a duplicate from before the skip existed: the sync leaves it alone, and a human cleans it up. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a tracking number on two live fulfillments is logged at warn with both`() {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(
      orderWithFoQuantities(remaining = 1).copy(
        fulfillments = listOf(fulfillment(8000L, listOf("TRK-A")), fulfillment(8001L, listOf("TRK-A"))),
      ),
    )
    val lines = capturingLogs {
      runBlocking { determineShopifyMutations(fake, ShopifyOrderId(1001L), listOf(shipment(tracking = "TRK-A"))) }
    }
    val line = lines.single { "sync-shipments skipped " in it }
    assert(line.startsWith("WARN sync-shipments skipped shipment orderId=1001 tracking=TRK-A reason=already_fulfilled fulfillmentIds=[8000, 8001]"))
  }
}
