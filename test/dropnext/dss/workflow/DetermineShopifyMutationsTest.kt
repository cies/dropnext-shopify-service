package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.graphql.generated.getorderfordss.Fulfillment
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
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

  @Test
  fun `missing order is NotFound`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.NotFound("order 1001 not found"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.NotFound)
    assert("order 1001 not found" in error.message)
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  @Test
  fun `Graphql errors with no order are GraphqlError`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.GraphqlError("throttled"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.GraphqlError)
    assert("throttled" in error.message)
  }

  @Test
  fun `load exception is Network`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Failure(ShopifyError.Network("connection refused"))
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    assert(result is Failure)
    val error = (result as Failure).reason
    assert(error is ShopifyError.Network)
    assert("connection refused" in error.message)
  }

  @Test
  fun `existing fulfillment is not planned as a cancel`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.orderForDssResult = Success(
      minimalOrder().copy(
        fulfillments = listOf(
          Fulfillment(
            id = "gid://shopify/Fulfillment/8000",
            legacyResourceId = "8000",
            trackingInfo = emptyList(),
          ),
        ),
      ),
    )
    val result = determineShopifyMutations(fake, ShopifyOrderId(1001L), shipments = listOf(shipment()))
    val mutations = (result as Success).value.mutations
    assert(mutations.none { it is ShopifyMutation.FulfillmentCancel })
    assert(mutations.single() is ShopifyMutation.FulfillmentCreate)
    assert(fake.cancelFulfillmentCalls.isEmpty())
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
        firstTotal = 1,
        secondVariantId = 202L,
        secondRemaining = 1,
        secondTotal = 1,
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

}
