package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * What the composition adds to its parts: the order is loaded once, a refused or failed step stops the run, the planned
 * creates are sent in payload order and their ids come back, and one summary line is logged. Which lines match which
 * fulfillment order is `CalculateShopifyMutationsTest`'s and `MatchShipmentToFulfillmentOrdersTest`'s; the wire format is
 * `HttpShopifyGraphqlServiceTest`'s; the request → response shape is `MonolithWebhookHandlersTest`'s.
 */
class SyncShopifyShipmentsToFulfillmentsTest {

  @Test
  fun `a failed order load is passed through and nothing is created`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.GraphqlError("throttled")) }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.failureReason() == ShopifyError.GraphqlError("throttled"))
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  fun `a plan the dry run refuses sends nothing to Shopify`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(quantity = 99))
    assert(result.failureReason() is ShopifyError.UserError)
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(fake.orderForDssCalls.size == 1)
  }

  @Test
  fun `syncShipments loads the order once and creates one fulfillment for a matching shipment`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5000L))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.newFulfillmentIds() == listOf(5000L))
    assert(fake.orderForDssCalls == listOf("gid://shopify/Order/1001"))
    assert(fake.createFulfillmentCalls.size == 1)
  }

  /** The order is read once for the whole payload: the plan already accounts for what each shipment claims. */
  @Test
  fun `every shipment is created in payload order from one order load and its id returned`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5002L))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit()))
    assert(result.newFulfillmentIds() == listOf(5001L, 5002L))
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("TRK-1", "TRK-2"))
    assert(fake.orderForDssCalls.size == 1)
  }

  @Test
  fun `syncShipments propagates a create UserError`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Failure(ShopifyError.UserError(listOf("tracking number invalid")))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.failureReason() == ShopifyError.UserError(listOf("tracking number invalid")))
  }

  /** The one line an operator searches for: it must name the order, the shop, the counts and the ids. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a successful run logs one summary line with the counts and the created ids`() {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5002L))
    }
    val lines = capturingLogs {
      runBlocking { syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit())) }
    }
    val line = lines.single { "sync-shipments " in it }
    assert(line.startsWith("INFO "))
    assert("orderId=1001" in line)
    assert("canceled=0" in line)
    assert("created=2" in line)
    assert("skippedShipments=0" in line)
    assert("fulfillmentIds=[5001, 5002]" in line)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the summary line counts a shipment skipped by its tracking number`() {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFoQuantities(remaining = 2).copy(fulfillments = listOf(fulfillment(5001L, listOf("TRK-A")))))
      createFulfillmentResult = Success(ShopifyFulfillmentId(5002L))
    }
    val lines = capturingLogs {
      runBlocking {
        syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = listOf(shipment(tracking = "TRK-A"), shipment(tracking = "TRK-B"))))
      }
    }
    val line = lines.single { "sync-shipments orderId=" in it }
    assert("created=1" in line)
    assert("skippedShipments=1" in line)
  }

  /** The recovery the tracking-number skip exists for, run end to end: the monolith re-sends the whole payload. */
  @Test
  fun `a retry after a partial failure skips the shipment already fulfilled by its tracking number and creates the rest`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithTwoVariantFulfillmentOrders())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Failure(ShopifyError.UserError(listOf("tracking number invalid")))
    }
    val payload = syncRequest(
      shipments = listOf(
        shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
        shipment(tracking = "TRK-2", variantId = 202L, quantity = 1),
      ),
    )
    val firstAttempt = syncShopifyShipmentsToFulfillments(fake, payload)
    assert(firstAttempt.failureReason() == ShopifyError.UserError(listOf("tracking number invalid")))
    assert(fake.createFulfillmentCalls.size == 2)

    // The retry sees the order as Shopify has it now: the first shipment is a fulfillment carrying its tracking number.
    fake.clear()
    fake.orderForDssResult = Success(
      orderWithTwoVariantFulfillmentOrders(firstRemaining = 0, secondRemaining = 1)
        .copy(fulfillments = listOf(fulfillment(5001L, listOf("TRK-1")))),
    )
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(6002L))
    val retry = syncShopifyShipmentsToFulfillments(fake, payload)
    assert(retry.newFulfillmentIds() == listOf(6002L))
    assert(fake.createFulfillmentCalls.single().tracking.number == "TRK-2")
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  // ---------- helpers ----------

  /** The created ids as the wire carries them; `successValue()` names the failure when the run was not a success. */
  private fun Result<List<ShopifyFulfillmentId>, ShopifyError>.newFulfillmentIds(): List<Long> =
    successValue().map { it.value }

  private fun syncRequest(
    quantity: Int = 1,
    shipments: List<Shipment> = listOf(shipment(quantity = quantity)),
  ): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      shipments = shipments,
    )

  private fun twoShipmentsOfOneUnit(): List<Shipment> = listOf(
    shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
    shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
  )
}
