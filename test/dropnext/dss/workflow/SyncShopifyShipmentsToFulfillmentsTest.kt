package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.diagramCrossFoOrder
import dropnext.dss.testutil.fixture.diagramCrossFoShipment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithFulfillment
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * The workflow against the in-memory fake: what is loaded, what is created, in which order, and what the
 * summary says. The wire format and the triage of Shopify's answers are `HttpShopifyGraphqlServiceTest`'s;
 * the request → response shape is `MonolithWebhookHandlersTest`'s.
 */
class SyncShopifyShipmentsToFulfillmentsTest {

  @Test
  fun `syncShipments returns NotFound when the order is missing`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.failureOrNull() is ShopifyError.NotFound)
    assert(fake.createFulfillmentCalls.isEmpty())
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

  @Test
  fun `syncShipments creates without canceling existing fulfillments`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFulfillment(id = 8000L))
      createFulfillmentResult = Success(ShopifyFulfillmentId(9000L))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.newFulfillmentIds() == listOf(9000L))
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  @Test
  fun `syncShipments propagates a create UserError`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Failure(ShopifyError.UserError(listOf("tracking number invalid")))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.failureOrNull() == ShopifyError.UserError(listOf("tracking number invalid")))
  }

  @Test
  fun `syncShipments passes a failed order load through`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.GraphqlError("throttled")) }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.failureOrNull() == ShopifyError.GraphqlError("throttled"))
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  fun `dry-run failure does not call create`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(quantity = 99))
    assert(result.failureOrNull() is ShopifyError.UserError)
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(fake.orderForDssCalls.size == 1)
  }

  @Test
  fun `returns new_fulfillment_ids for each created shipment`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5002L))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit()))
    assert(result.newFulfillmentIds() == listOf(5001L, 5002L))
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
  fun `does not reload the order between two shipment creates`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5002L))
    }
    syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit()))
    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.size == 2)
  }

  @Test
  fun `skips create when all lines are unmatched`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(variantId = 999L))
    assert(result.newFulfillmentIds().isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  fun `a partially matched shipment still creates a fulfillment for the matched lines`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
    }
    val mixed = Shipment(
      trackingNumber = "TRK-MIXED",
      carrier = "UPS",
      trackingUrl = null,
      lineItems = listOf(
        ShipmentLineItem(productVariantId = 101L, quantity = 1),
        ShipmentLineItem(productVariantId = 999L, quantity = 1),
      ),
    )
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = listOf(mixed)))
    assert(result.newFulfillmentIds() == listOf(5001L))
    assert(fake.createFulfillmentCalls.single().lines.size == 1)
  }

  @Test
  fun `diagram cross-FO shipment creates one fulfillment spanning two fulfillment orders`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(diagramCrossFoOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5100L))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = listOf(diagramCrossFoShipment())))
    assert(result.newFulfillmentIds() == listOf(5100L))
    val create = fake.createFulfillmentCalls.single()
    val foIds = create.lines.map { it.fulfillmentOrderId }.toSet()
    assert(foIds == setOf("gid://shopify/FulfillmentOrder/301", "gid://shopify/FulfillmentOrder/302"))
    assert(create.tracking.number == "TRK-A")
  }

  @Test
  fun `mixed shipments skip the all-unmatched one and create for the matched one`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5200L))
    }
    val result = syncShopifyShipmentsToFulfillments(
      fake,
      syncRequest(
        shipments = listOf(
          shipment(tracking = "TRK-BAD", variantId = 999L, quantity = 1),
          shipment(tracking = "TRK-GOOD", variantId = 101L, quantity = 1),
        ),
      ),
    )
    assert(result.newFulfillmentIds() == listOf(5200L))
    assert(fake.createFulfillmentCalls.single().tracking.number == "TRK-GOOD")
  }

  @Test
  fun `partial failure recovery on retry skips the fulfilled variant and creates the rest`() = runBlocking {
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
    assert(firstAttempt.failureOrNull() is ShopifyError.UserError)
    assert(fake.createFulfillmentCalls.size == 2)

    // The retry sees the order as Shopify has it now: the first variant's unit is fulfilled.
    fake.clear()
    fake.orderForDssResult = Success(
      orderWithTwoVariantFulfillmentOrders(firstRemaining = 0, firstTotal = 1, secondRemaining = 1, secondTotal = 1),
    )
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(6002L))
    val retry = syncShopifyShipmentsToFulfillments(fake, payload)
    assert(retry.newFulfillmentIds() == listOf(6002L))
    assert(fake.createFulfillmentCalls.single().tracking.number == "TRK-2")
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  @Test
  fun `remaining quantity creates without canceling the existing fulfillment`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFoQuantities(remaining = 1, total = 2))
      createFulfillmentResult = Success(ShopifyFulfillmentId(9001L))
    }
    val result = syncShopifyShipmentsToFulfillments(
      fake,
      syncRequest(shipments = listOf(shipment(tracking = "TRK-2", variantId = 101L, quantity = 1))),
    )
    assert(result.newFulfillmentIds() == listOf(9001L))
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.size == 1)
  }

  @Test
  fun `a second item shipped later creates without canceling the first fulfillment`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(
        orderWithTwoVariantFulfillmentOrders(firstRemaining = 0, firstTotal = 1, secondRemaining = 1, secondTotal = 1),
      )
      createFulfillmentResult = Success(ShopifyFulfillmentId(9002L))
    }
    val result = syncShopifyShipmentsToFulfillments(
      fake,
      syncRequest(shipments = listOf(shipment(tracking = "TRK-O2", variantId = 202L, quantity = 1))),
    )
    assert(result.newFulfillmentIds() == listOf(9002L))
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.size == 1)
  }

  @Test
  fun `an already fulfilled variant is skipped with no cancel`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(orderWithFoQuantities(remaining = 0, total = 1)) }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.newFulfillmentIds().isEmpty())
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  // ---------- helpers ----------

  /** The created ids as the wire carries them, asserting the result was a success on the way. */
  private fun Result<List<ShopifyFulfillmentId>, ShopifyError>.newFulfillmentIds(): List<Long> {
    assert(this is Success)
    return (this as Success).value.map { it.value }
  }

  private fun Result<List<ShopifyFulfillmentId>, ShopifyError>.failureOrNull(): ShopifyError? =
    (this as? Failure)?.reason

  private fun syncRequest(
    variantId: Long = 101L,
    quantity: Int = 1,
    shipments: List<Shipment>? = null,
  ): SyncShipmentsWithFulfillmentsRequest =
    SyncShipmentsWithFulfillmentsRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = 1001L,
      shipments = shipments ?: listOf(shipment(variantId = variantId, quantity = quantity)),
    )

  private fun twoShipmentsOfOneUnit(): List<Shipment> = listOf(
    shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
    shipment(tracking = "TRK-2", variantId = 101L, quantity = 1),
  )
}
