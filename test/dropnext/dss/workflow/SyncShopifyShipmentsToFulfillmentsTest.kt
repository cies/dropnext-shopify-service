package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.Shipment
import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.domain.FulfillmentCancellation
import dropnext.dss.domain.FulfillmentCancellationStatus
import dropnext.dss.domain.ShipmentSync
import dropnext.dss.domain.ShipmentSyncReport
import dropnext.dss.domain.ShipmentSyncStatus
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.fulfillment.PackageContent
import dropnext.dss.domain.fulfillment.SkipReason
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.fulfillmentWithGid
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.orderWithTwoVariantFulfillmentOrders
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.MutableTimeSource
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.getorderfordss.Order
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/**
 * What the composition adds to its parts: the order is loaded once, the planned creates are sent in payload order, a
 * refused create is an outcome and a failed step stops the run, every shipment ends up in the report, and one summary
 * line is logged. How the report is assembled is `BuildShipmentSyncReportTest`'s; which lines match which fulfillment
 * order is `CalculateShopifyMutationsTest`'s and `MatchShipmentToFulfillmentOrdersTest`'s; the wire format is
 * `HttpShopifyGraphqlServiceTest`'s; the request → response shape is `MonolithWebhookHandlersTest`'s.
 */
private const val X = 101L
private const val Y = 202L
private const val NEW_FO_X = "gid://shopify/FulfillmentOrder/303"
private const val NEW_FO_Y = "gid://shopify/FulfillmentOrder/304"
private val F1 = fulfillment(8001L, listOf("T1"))
private val F2 = fulfillment(8002L, listOf("T2"))
private val canceledF1 = fulfillment(8001L, listOf("T1"), status = FulfillmentStatus.CANCELLED)

class SyncShopifyShipmentsToFulfillmentsTest {

  /** Nothing was classified before the stop, so the report claims nothing about any shipment. */
  @Test
  fun `a failed order load stops the run with every shipment not attempted`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.GraphqlError("throttled")) }
    val stopped = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit())).failureReason()
    assert(stopped.cause == ShopifyError.GraphqlError("throttled"))
    assert(stopped.report.shipments.map { it.status }.all { it == ShipmentSyncStatus.NotAttempted })
    assert(stopped.report.shipments.map { it.trackingNumber } == listOf("TRK-1", "TRK-2"))
    assert(stopped.report.cancellations.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  /** An over-shipment is left out of Shopify, and the run still succeeds. */
  @Test
  fun `a shipment that does not fit sends nothing to Shopify and the run succeeds`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(minimalOrder()) }
    val report = syncShopifyShipmentsToFulfillments(fake, syncRequest(quantity = 99)).successValue()
    assert(report.shipments == listOf(ShipmentSync("1Z999", ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM))))
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(fake.orderForDssCalls.size == 1)
  }

  /** A re-ship entered before the job ran: the original is created, the re-ship finds no room left. */
  @Test
  fun `a re-ship in the same payload as the original creates the original only`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
    }
    val payload = syncRequest(
      shipments = listOf(shipment(tracking = "TRK-A", quantity = 2), shipment(tracking = "TRK-B", quantity = 2)),
    )
    val report = syncShopifyShipmentsToFulfillments(fake, payload).successValue()
    val expected = listOf(
      ShipmentSync("TRK-A", ShipmentSyncStatus.Created(ShopifyFulfillmentId(5001L))),
      ShipmentSync("TRK-B", ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM)),
    )
    assert(report.shipments == expected)
    assert(fake.createFulfillmentCalls.single().tracking.number == "TRK-A")
  }

  @Test
  fun `syncShipments loads the order once and creates one fulfillment for a matching shipment`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5000L))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest())
    assert(result.createdIds() == listOf(5000L))
    assert(fake.orderForDssCalls == listOf("gid://shopify/Order/1001"))
    assert(fake.createFulfillmentCalls.size == 1)
  }

  /** The order is read once for the whole payload: the plan already accounts for what each shipment claims. */
  @Test
  fun `every shipment is created in payload order from one order load and its id reported`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5002L))
    }
    val result = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit()))
    assert(result.createdIds() == listOf(5001L, 5002L))
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("TRK-1", "TRK-2"))
    assert(fake.orderForDssCalls.size == 1)
  }

  /** Shopify would refuse the same create on every run, so a refusal must not keep the order's other shipments out. */
  @Test
  fun `syncShipments reports a create UserError as refused and finishes the run`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Failure(ShopifyError.UserError(listOf("tracking number invalid")))
    }
    val report = syncShopifyShipmentsToFulfillments(fake, syncRequest()).successValue()
    val refused = ShipmentSyncStatus.Refused(listOf("tracking number invalid"))
    assert(report.shipments == listOf(ShipmentSync("1Z999", refused)))
  }

  @Test
  fun `a mixed payload reports each shipment with its own outcome in request order`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(
        orderWithTwoVariantFulfillmentOrders().copy(fulfillments = listOf(fulfillment(3990L, listOf("TRK-DONE")))),
      )
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(4001L))
      createFulfillmentResultQueue += Failure(ShopifyError.UserError(listOf("tracking number invalid")))
    }
    val payload = syncRequest(
      shipments = listOf(
        shipment(tracking = "TRK-NEW", variantId = 101L),
        shipment(tracking = "TRK-DONE", variantId = 101L),
        shipment(tracking = "TRK-BIG", variantId = 202L, quantity = 5),
        shipment(tracking = "TRK-ALIEN", variantId = 999L),
        shipment(tracking = "TRK-BAD", variantId = 202L),
      ),
    )
    val report = syncShopifyShipmentsToFulfillments(fake, payload).successValue()
    val expected = listOf(
      ShipmentSync("TRK-NEW", ShipmentSyncStatus.Created(ShopifyFulfillmentId(4001L))),
      ShipmentSync("TRK-DONE", ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(3990L))),
      ShipmentSync("TRK-BIG", ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM)),
      ShipmentSync("TRK-ALIEN", ShipmentSyncStatus.Skipped(SkipReason.NOT_ON_ORDER)),
      ShipmentSync("TRK-BAD", ShipmentSyncStatus.Refused(listOf("tracking number invalid"))),
    )
    assert(report.shipments == expected)
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("TRK-NEW", "TRK-BAD"))
  }

  /** The monolith finds its shipment by the string it sent. */
  @Test
  fun `a tracking number with surrounding spaces is reported as sent`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
    }
    val report = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = listOf(shipment(tracking = " TRK-1 "))))
    assert(report.successValue().shipments.single().trackingNumber == " TRK-1 ")
  }

  @Test
  fun `a create that fails on the wire stops the run and reports what landed before it`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithFoQuantities(remaining = 3))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Failure(ShopifyError.HttpError(503))
    }
    val shipments = twoShipmentsOfOneUnit() + shipment(tracking = "TRK-3", variantId = 101L)
    val stopped = syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = shipments)).failureReason()
    val expected = listOf(
      ShipmentSync("TRK-1", ShipmentSyncStatus.Created(ShopifyFulfillmentId(5001L))),
      ShipmentSync("TRK-2", ShipmentSyncStatus.Failed),
      ShipmentSync("TRK-3", ShipmentSyncStatus.NotAttempted),
    )
    assert(stopped.cause == ShopifyError.HttpError(503))
    assert(stopped.report.shipments == expected)
    assert(fake.createFulfillmentCalls.size == 2)
  }

  /** The one line an operator searches for: it must name the order, the counts and the created ids. */
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
    val line = lines.single { "sync-shipments orderId=" in it }
    assert(line.startsWith("INFO "))
    assert("orderId=1001 " in line)
    assert("created=2 " in line)
    assert("alreadyFulfilled=0 " in line)
    assert("skipped=0 " in line)
    assert("blocked=0 " in line)
    assert("refused=0 " in line)
    assert("canceled=0 " in line)
    assert("cancelRefused=0 " in line)
    assert("notified=2 " in line)
    assert("stoppedBy=none " in line)
    assert("fulfillmentIds=[5001, 5002]" in line)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the summary line counts only the creates that notified the customer`() {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(
        orderWithFoQuantities(remaining = 2).copy(fulfillments = listOf(fulfillment(4000L, listOf("TRK-1"), status = FulfillmentStatus.CANCELLED))),
      )
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5002L))
    }
    val lines = capturingLogs {
      runBlocking { syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit())) }
    }
    val line = lines.single { "sync-shipments orderId=" in it }
    assert("created=2 " in line)
    assert("notified=1 " in line)
  }

  /** What the plan decided reaches Shopify: the customer is told of a new number, not of one the order already had. */
  @Test
  fun `each create reaches Shopify with the notification the plan decided`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(
        orderWithFoQuantities(remaining = 2).copy(fulfillments = listOf(fulfillment(4000L, listOf("TRK-1"), status = FulfillmentStatus.CANCELLED))),
      )
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5002L))
    }
    syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit()))
    assert(fake.createFulfillmentCalls.map { it.tracking.number to it.notifyCustomer } == listOf("TRK-1" to false, "TRK-2" to true))
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the summary line counts a shipment already fulfilled under its tracking number`() {
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
    assert("created=1 " in line)
    assert("alreadyFulfilled=1 " in line)
    assert("skipped=0 " in line)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the summary line counts a shipment skipped for lack of room`() {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
    }
    val lines = capturingLogs {
      runBlocking {
        syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = listOf(shipment(tracking = "TRK-A", quantity = 2), shipment(tracking = "TRK-B"))))
      }
    }
    val line = lines.single { "sync-shipments orderId=" in it }
    assert("created=1 " in line)
    assert("skipped=1 " in line)
    assert("alreadyFulfilled=0 " in line)
  }

  /** A refusal is the outcome that needs a human, so it gets a line of its own, with what Shopify said. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a refused create is logged at error with Shopify's messages and counted in the summary`() {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResult = Failure(ShopifyError.UserError(listOf("tracking number invalid", "carrier unknown")))
    }
    val lines = capturingLogs {
      runBlocking { syncShopifyShipmentsToFulfillments(fake, syncRequest()) }
    }
    val refusal = lines.single { "sync-shipments create refused" in it }
    assert(refusal.startsWith("ERROR "))
    assert("orderId=1001 " in refusal)
    assert("tracking=1Z999 " in refusal)
    assert("error=tracking number invalid; carrier unknown" in refusal)
    val summary = lines.single { "sync-shipments orderId=" in it }
    assert(summary.startsWith("INFO "))
    assert("refused=1 " in summary)
    assert("created=0 " in summary)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a stopped run logs its summary at warn naming what stopped it`() {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Failure(ShopifyError.HttpError(503))
    }
    val lines = capturingLogs {
      runBlocking { syncShopifyShipmentsToFulfillments(fake, syncRequest(shipments = twoShipmentsOfOneUnit())) }
    }
    val line = lines.single { "sync-shipments orderId=" in it }
    assert(line.startsWith("WARN "))
    assert("created=1 " in line)
    assert("stoppedBy=shopify_http_503 " in line)
    assert("fulfillmentIds=[5001]" in line)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a run stopped by the order load still logs its summary`() {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.NotFound("order not found")) }
    val lines = capturingLogs {
      runBlocking { syncShopifyShipmentsToFulfillments(fake, syncRequest()) }
    }
    val line = lines.single { "sync-shipments orderId=" in it }
    assert(line.startsWith("WARN "))
    assert("created=0 " in line)
    assert("stoppedBy=shopify_not_found " in line)
  }

  /** The recovery the tracking-number skip exists for, run end to end: the monolith re-sends the whole payload. */
  @Test
  fun `a retry after a partial failure reports the shipment created before as already fulfilled and creates the rest`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(orderWithTwoVariantFulfillmentOrders())
      createFulfillmentResultQueue += Success(ShopifyFulfillmentId(5001L))
      createFulfillmentResultQueue += Failure(ShopifyError.Network("connection reset"))
    }
    val payload = syncRequest(
      shipments = listOf(
        shipment(tracking = "TRK-1", variantId = 101L, quantity = 1),
        shipment(tracking = "TRK-2", variantId = 202L, quantity = 1),
      ),
    )
    val firstAttempt = syncShopifyShipmentsToFulfillments(fake, payload).failureReason()
    assert(firstAttempt.cause == ShopifyError.Network("connection reset"))
    assert(firstAttempt.report.shipments.map { it.status } == listOf(ShipmentSyncStatus.Created(ShopifyFulfillmentId(5001L)), ShipmentSyncStatus.Failed))
    assert(fake.createFulfillmentCalls.size == 2)

    // The retry sees the order as Shopify has it now: the first shipment is a fulfillment carrying its tracking number.
    fake.clear()
    fake.orderForDssResult = Success(
      orderWithTwoVariantFulfillmentOrders(firstRemaining = 0, secondRemaining = 1)
        .copy(fulfillments = listOf(fulfillment(5001L, listOf("TRK-1")))),
    )
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(6002L))
    val retry = syncShopifyShipmentsToFulfillments(fake, payload).successValue()
    val expected = listOf(
      ShipmentSync("TRK-1", ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(5001L))),
      ShipmentSync("TRK-2", ShipmentSyncStatus.Created(ShopifyFulfillmentId(6002L))),
    )
    assert(retry.shipments == expected)
    assert(fake.createFulfillmentCalls.single().tracking.number == "TRK-2")
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  // ---------- retired tracking numbers ----------

  /**
   * The split from the design: A (T1: 2 × X, 1 × Y) was mirrored as F1 and is retired; B keeps T1 with the X only, and
   * C gets T2 with the Y. Before the cancel F1's fulfillment order is closed; after it, Shopify has put the units on a
   * new one.
   */
  @Test
  fun `a split that reuses a number cancels the stale fulfillment, reloads and creates both shipments`() = runBlocking {
    val fake = splitShop()

    val report = syncShopifyShipmentsToFulfillments(fake, splitRequest()).successValue()

    assert(fake.fulfillmentContentCalls == listOf(F1.id))
    assert(fake.cancelFulfillmentCalls == listOf(F1.id))
    assert(fake.orderForDssCalls.size == 2)
    assert(fake.createFulfillmentCalls.map { it.tracking.number to it.notifyCustomer } == listOf("T1" to false, "T2" to true))
    assert(fake.createFulfillmentCalls.flatMap { it.lines }.map { it.fulfillmentOrderId }.toSet() == setOf(NEW_FO_X, NEW_FO_Y))
    assert(report.shipments == listOf(
      ShipmentSync("T1", ShipmentSyncStatus.Created(ShopifyFulfillmentId(6001L))),
      ShipmentSync("T2", ShipmentSyncStatus.Created(ShopifyFulfillmentId(6002L))),
    ))
    assert(report.cancellations == listOf(FulfillmentCancellation("T1", ShopifyFulfillmentId(8001L), FulfillmentCancellationStatus.Canceled)))
  }

  /** Every run sends the retired number again: B's own fulfillment now carries it, with B's content, so it stays. */
  @Test
  fun `the same split sent again cancels nothing, does not reload and finds both shipments fulfilled`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(
        afterSplitCancel(xRemaining = 0, yRemaining = 0).copy(
          fulfillments = listOf(canceledF1, fulfillment(6001L, listOf("T1")), fulfillment(6002L, listOf("T2"))),
        ),
      )
      fulfillmentContentResults["gid://shopify/Fulfillment/6001"] = Success(PackageContent.Known(mapOf(X to 2)))
    }

    val report = syncShopifyShipmentsToFulfillments(fake, splitRequest()).successValue()

    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(report.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(6001L)),
      ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(6002L)),
    ))
    assert(report.cancellations.isEmpty())
  }

  /** A package that did not change keeps its fulfillment, and with it its tracking events. */
  @Test
  fun `a split that keeps a number's lines cancels nothing and creates the new shipment only`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(beforeSplit(yRemaining = 1).copy(fulfillments = listOf(fulfillment(8001L, listOf("T1")))))
      fulfillmentContentResults[F1.id] = Success(PackageContent.Known(mapOf(X to 2)))
      createFulfillmentResult = Success(ShopifyFulfillmentId(6002L))
    }

    val report = syncShopifyShipmentsToFulfillments(fake, splitRequest()).successValue()

    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T2"))
    assert(report.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(8001L)),
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(6002L)),
    ))
  }

  /** B asks three X where F1 gives back one and the order has none open: F1 stays, and B waits for room. */
  @Test
  fun `a split that puts more under a reused number than there is room for keeps the stale fulfillment`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(beforeSplit(yRemaining = 1).copy(fulfillments = listOf(F1)))
      fulfillmentContentResults[F1.id] = Success(PackageContent.Known(mapOf(X to 1)))
      createFulfillmentResult = Success(ShopifyFulfillmentId(6002L))
    }
    val request = splitRequest(b = shipment(tracking = "T1", variantId = X, quantity = 3))

    val report = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T2"))
    assert(report.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.Blocked(ShopifyFulfillmentId(8001L)),
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(6002L)),
    ))
    assert(report.cancellations.isEmpty())
  }

  /** An older shipment that never fit must not take the units F1's cancel gives back to B. */
  @Test
  fun `the replacing shipment gets the room its cancel freed before an older shipment ahead of it`() = runBlocking {
    val fake = splitShop()
    val request = splitRequest().let { it.copy(shipments = listOf(shipment(tracking = "T0", variantId = X, quantity = 2)) + it.shipments) }

    val report = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T1", "T2"))
    assert(report.shipments.map { it.trackingNumber to it.status } == listOf(
      "T0" to ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM),
      "T1" to ShipmentSyncStatus.Created(ShopifyFulfillmentId(6001L)),
      "T2" to ShipmentSyncStatus.Created(ShopifyFulfillmentId(6002L)),
    ))
  }

  @Test
  fun `a request without retired numbers loads the order once and no content`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(minimalOrder().copy(fulfillments = listOf(fulfillment(8001L, listOf("TRK-OLD")))))
      createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
    }

    syncShopifyShipmentsToFulfillments(fake, syncRequest()).successValue()

    assert(fake.orderForDssCalls.size == 1)
    assert(fake.fulfillmentContentCalls.isEmpty())
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  /** Decided: a refused cancel is tried again on every run, and B waits behind it; C has room of its own. */
  @Test
  fun `a refused cancel blocks the shipment under its number and the others still go`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(beforeSplit(yRemaining = 1).copy(fulfillments = listOf(F1)))
      fulfillmentContentResults[F1.id] = Success(PackageContent.Known(mapOf(X to 2)))
      cancelFulfillmentResult = Failure(ShopifyError.UserError(listOf("Fulfillment has already been delivered.")))
      createFulfillmentResult = Success(ShopifyFulfillmentId(6002L))
    }

    val report = syncShopifyShipmentsToFulfillments(fake, splitRequest(b = shipment(tracking = "T1", variantId = X, quantity = 1))).successValue()

    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T2"))
    assert(report.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.Blocked(ShopifyFulfillmentId(8001L)),
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(6002L)),
    ))
    val refused = FulfillmentCancellationStatus.Refused(listOf("Fulfillment has already been delivered."))
    assert(report.cancellations == listOf(FulfillmentCancellation("T1", ShopifyFulfillmentId(8001L), refused)))
  }

  /** A reload that is too quick to show the cancel must not make B look fulfilled by the fulfillment just canceled. */
  @Test
  fun `a reload that still shows the canceled fulfillment live does not make its shipment fulfilled`() = runBlocking {
    val fake = splitShop(reloaded = afterSplitCancel(xRemaining = 2, yRemaining = 1).copy(fulfillments = listOf(F1)))

    val report = syncShopifyShipmentsToFulfillments(fake, splitRequest()).successValue()

    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T1", "T2"))
    assert(report.shipments.first().status == ShipmentSyncStatus.Created(ShopifyFulfillmentId(6001L)))
  }

  /**
   * Shopify keeps a canceled fulfillment, so an id the order lists and Shopify does not have is an anomaly. The order
   * snapshot decides: F1 stays in the way of T1, and B is not given a second fulfillment under the same number.
   */
  @Test
  fun `a fulfillment gone before its content was read keeps its number blocked`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(beforeSplit(xRemaining = 2, yRemaining = 1).copy(fulfillments = listOf(F1)))
      fulfillmentContentResults[F1.id] = Failure(ShopifyError.NotFound("fulfillment 8001 not found"))
      createFulfillmentResult = Success(ShopifyFulfillmentId(6001L))
    }

    val report = syncShopifyShipmentsToFulfillments(fake, splitRequest()).successValue()

    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T2"))
    assert(report.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.Blocked(ShopifyFulfillmentId(8001L)),
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(6001L)),
    ))
    assert(report.cancellations.isEmpty())
  }

  @Test
  fun `a request of retired numbers only cancels and creates nothing`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Success(beforeSplit().copy(fulfillments = listOf(F1))) }
    val request = splitRequest().copy(shipments = emptyList())

    val report = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.fulfillmentContentCalls.isEmpty())
    assert(fake.cancelFulfillmentCalls == listOf(F1.id))
    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(report.shipments.isEmpty())
    assert(report.cancellations.single().status == FulfillmentCancellationStatus.Canceled)
  }

  /** The cancel landed, so the report says so; the retry finds F1 canceled and only creates. */
  @Test
  fun `a reload that fails after a cancel reports the cancel, and the retry creates what is missing`() = runBlocking {
    val fake = splitShop()
    fake.orderForDssResultQueue[1] = Failure(ShopifyError.HttpError(503))

    val stopped = syncShopifyShipmentsToFulfillments(fake, splitRequest()).failureReason()

    assert(stopped.cause == ShopifyError.HttpError(503))
    assert(stopped.report.cancellations.single().status == FulfillmentCancellationStatus.Canceled)
    assert(stopped.report.shipments.map { it.status } == listOf(ShipmentSyncStatus.NotAttempted, ShipmentSyncStatus.NotAttempted))
    assert(fake.createFulfillmentCalls.isEmpty())

    val retried = retryAfterTheCancel(fake)
    assert(retried.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(7001L)),
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(7002L)),
    ))
    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.orderForDssCalls.size == 1)
  }

  /** Without a cancel in the retry, B is no longer recognized as replacing; it is planned in request order. */
  @Test
  fun `a create that fails after a cancel stops the run, and the retry creates the rest`() = runBlocking {
    val fake = splitShop()
    fake.createFulfillmentResultQueue.clear()
    fake.createFulfillmentResultQueue += Failure(ShopifyError.Network("connection reset"))

    val stopped = syncShopifyShipmentsToFulfillments(fake, splitRequest()).failureReason()

    assert(stopped.report.cancellations.single().status == FulfillmentCancellationStatus.Canceled)
    assert(stopped.report.shipments.map { it.status } == listOf(ShipmentSyncStatus.Failed, ShipmentSyncStatus.NotAttempted))

    val retried = retryAfterTheCancel(fake)
    assert(retried.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(7001L)),
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(7002L)),
    ))
    assert(retried.cancellations.isEmpty())
  }

  /** Each call takes three seconds: after the load, the content, the cancel, the reload and one create, none can start. */
  @Test
  fun `a run that would outlive its budget stops before the next call and reports what it did`() = runBlocking {
    val clock = MutableTimeSource()
    val fake = splitShop().apply {
      shipmentSyncTimeSource = clock
      shipmentSyncCallTakes = 3.seconds
    }
    val budget = ShipmentSyncBudget.startedAt(clock.markNow(), 25.seconds)

    val stopped = syncShopifyShipmentsToFulfillments(fake, splitRequest(), budget).failureReason()

    assert(stopped.cause == ShopifyError.TimedOut("syncing the shipments", 25.seconds))
    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T1"))
    assert(stopped.report.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.Created(ShopifyFulfillmentId(6001L)),
      ShipmentSyncStatus.NotAttempted,
    ))
    assert(stopped.report.cancellations.single().status == FulfillmentCancellationStatus.Canceled)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `the summary line counts the cancels, the refused ones and the blocked shipments`() {
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(beforeSplit(yRemaining = 1).copy(fulfillments = listOf(F1)))
      fulfillmentContentResults[F1.id] = Success(PackageContent.Known(mapOf(X to 2)))
      cancelFulfillmentResult = Failure(ShopifyError.UserError(listOf("Fulfillment has already been delivered.")))
      createFulfillmentResult = Success(ShopifyFulfillmentId(6002L))
    }
    val lines = capturingLogs {
      runBlocking { syncShopifyShipmentsToFulfillments(fake, splitRequest(b = shipment(tracking = "T1", variantId = X, quantity = 1))) }
    }
    val refusal = lines.single { "sync-shipments cancel refused" in it }
    assert(refusal.startsWith("ERROR sync-shipments cancel refused orderId=1001 tracking=T1 fulfillmentId=8001 error=Fulfillment has already been delivered."))
    val line = lines.single { "sync-shipments orderId=" in it }
    assert("created=1 " in line)
    assert("blocked=1 " in line)
    assert("canceled=0 " in line)
    assert("cancelRefused=1 " in line)
  }

  /**
   * The run that canceled F1 stopped before it could create B, and the retry has no cancel of its own. It must still
   * prefer B: Shopify has carried B's number, so the customer is tracking that package, while T0 never fit.
   */
  @Test
  fun `after a stop between the cancel and the creates, the retry still prefers the shipment Shopify carried`() = runBlocking {
    val fake = splitShop()
    fake.orderForDssResultQueue[1] = Failure(ShopifyError.HttpError(503))
    val request = withOlderShipment(splitRequest())

    val stopped = syncShopifyShipmentsToFulfillments(fake, request).failureReason()
    assert(stopped.report.cancellations.single().status == FulfillmentCancellationStatus.Canceled)

    val retried = retryAfterTheCancel(fake, request)

    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T1", "T2"))
    assert(retried.shipments.map { it.trackingNumber to it.status } == listOf(
      "T0" to ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM),
      "T1" to ShipmentSyncStatus.Created(ShopifyFulfillmentId(7001L)),
      "T2" to ShipmentSyncStatus.Created(ShopifyFulfillmentId(7002L)),
    ))
  }

  /** The reload came too early to show the canceled units: the next run must still give them to B, not to T0. */
  @Test
  fun `a reload without the units back leaves the shipment to the next run, still ahead of an older one`() = runBlocking {
    val reloaded = afterSplitCancel(xRemaining = 0, yRemaining = 1).copy(fulfillments = listOf(canceledF1))
    val fake = splitShop(reloaded = reloaded)
    val request = withOlderShipment(splitRequest())

    val report = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T2"))
    assert(report.shipments.map { it.trackingNumber to it.status } == listOf(
      "T0" to ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM),
      "T1" to ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM),
      "T2" to ShipmentSyncStatus.Created(ShopifyFulfillmentId(6001L)),
    ))

    // The next run of the order, with the units back and T2 mirrored.
    fake.clear()
    fake.orderForDssResult = Success(
      afterSplitCancel(xRemaining = 2, yRemaining = 0)
        .copy(fulfillments = listOf(canceledF1, fulfillment(6001L, listOf("T2")))),
    )
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(7001L))
    val next = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.createFulfillmentCalls.map { it.tracking.number } == listOf("T1"))
    assert(next.shipments.map { it.trackingNumber to it.status } == listOf(
      "T0" to ShipmentSyncStatus.Skipped(SkipReason.NO_ROOM),
      "T1" to ShipmentSyncStatus.Created(ShopifyFulfillmentId(7001L)),
      "T2" to ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(6001L)),
    ))
  }

  /**
   * A fulfillment carrying two numbers was made by hand. Canceling it would guess which of the two packages it holds,
   * so it is kept and reported as the fulfillment of both; the warn line is what a human acts on.
   */
  @Test
  fun `a fulfillment under two reused numbers is kept, and both shipments are reported as fulfilled by it`() = runBlocking {
    val both = fulfillment(8001L, listOf("T1", "T2"))
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(beforeSplit(xRemaining = 2, yRemaining = 1).copy(fulfillments = listOf(both)))
      fulfillmentContentResults[both.id] = Success(PackageContent.Known(mapOf(X to 9)))
    }

    val report = syncShopifyShipmentsToFulfillments(fake, splitRequest().copy(retiredTrackingNumbers = listOf("T1", "T2"))).successValue()

    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(report.shipments.map { it.status } == listOf(
      ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(8001L)),
      ShipmentSyncStatus.AlreadyFulfilled(ShopifyFulfillmentId(8001L)),
    ))
    assert(report.cancellations.isEmpty())
  }

  // ---------- the cancels in two waves ----------

  /**
   * B counted on the units of both fulfillments. F2's cancel is refused, so the run does not take F1 away too: B keeps
   * an outdated fulfillment rather than none.
   */
  @Test
  fun `a refused cancel of a retired number drops the content-changed cancel its shipment counted on`() = runBlocking {
    val fake = twoFulfillmentShop().apply {
      cancelFulfillmentResults[F2.id] = Failure(ShopifyError.UserError(listOf("Fulfillment has already been delivered.")))
    }
    val request = syncRequest(shipments = listOf(shipment(tracking = "T1", variantId = X, quantity = 2)))
      .copy(retiredTrackingNumbers = listOf("T1", "T2"))

    val report = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.cancelFulfillmentCalls == listOf(F2.id))
    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(report.shipments.single().status == ShipmentSyncStatus.Blocked(ShopifyFulfillmentId(8001L)))
    val refused = FulfillmentCancellationStatus.Refused(listOf("Fulfillment has already been delivered."))
    assert(report.cancellations == listOf(FulfillmentCancellation("T2", ShopifyFulfillmentId(8002L), refused)))
  }

  /** The same order with the first wave going through: the second wave follows, and B replaces its fulfillment. */
  @Test
  fun `the content-changed cancel follows once the retired one went through`() = runBlocking {
    val fake = twoFulfillmentShop()
    val request = syncRequest(shipments = listOf(shipment(tracking = "T1", variantId = X, quantity = 2)))
      .copy(retiredTrackingNumbers = listOf("T1", "T2"))

    val report = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.cancelFulfillmentCalls == listOf(F2.id, F1.id))
    assert(report.cancellations.map { it.trackingNumber to it.status } ==
      listOf("T2" to FulfillmentCancellationStatus.Canceled, "T1" to FulfillmentCancellationStatus.Canceled))
    assert(report.shipments.single().status == ShipmentSyncStatus.Created(ShopifyFulfillmentId(6001L)))
  }

  /** A cancel that does not reach Shopify stops the run before the order is loaded again. */
  @Test
  fun `a cancel that fails on the wire stops the run and reports what it canceled`() = runBlocking {
    val fake = twoFulfillmentShop().apply { cancelFulfillmentResults[F2.id] = Failure(ShopifyError.HttpError(503)) }
    val request = syncRequest(shipments = emptyList()).copy(retiredTrackingNumbers = listOf("T2", "T3"))
    fake.orderForDssResultQueue[0] = Success(
      beforeSplit().copy(fulfillments = listOf(F2, fulfillment(8003L, listOf("T3")))),
    )

    val stopped = syncShopifyShipmentsToFulfillments(fake, request).failureReason()

    assert(stopped.cause == ShopifyError.HttpError(503))
    assert(stopped.report.cancellations.map { it.trackingNumber to it.status } == listOf(
      "T2" to FulfillmentCancellationStatus.Failed,
      "T3" to FulfillmentCancellationStatus.NotAttempted,
    ))
    assert(fake.orderForDssCalls.size == 1)
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  /** Shopify's ids always carry a number; a gid without one cannot be named in a mutation, so nothing is sent for it. */
  @Test
  fun `a fulfillment whose gid carries no numeric id is not canceled, and blocks its shipment`() = runBlocking {
    val odd = fulfillmentWithGid("gid://shopify/Fulfillment/not-a-number", listOf("T1"))
    val fake = FakeShopifyGraphqlService().apply {
      orderForDssResult = Success(beforeSplit(xRemaining = 2).copy(fulfillments = listOf(odd)))
      fulfillmentContentResults[odd.id] = Success(PackageContent.Known(mapOf(X to 1)))
    }
    val request = syncRequest(shipments = listOf(shipment(tracking = "T1", variantId = X, quantity = 2)))
      .copy(retiredTrackingNumbers = listOf("T1"))

    val report = syncShopifyShipmentsToFulfillments(fake, request).successValue()

    assert(fake.cancelFulfillmentCalls.isEmpty())
    assert(fake.createFulfillmentCalls.isEmpty())
    assert(report.shipments.single().status == ShipmentSyncStatus.Blocked(fulfillmentId = null))
    assert(report.cancellations.isEmpty())
  }

  // ---------- helpers ----------

  /** The order before the split's cancel: X and Y are on fulfillment orders with [xRemaining] and [yRemaining] left. */
  private fun beforeSplit(xRemaining: Int = 0, yRemaining: Int = 0) =
    orderWithTwoVariantFulfillmentOrders(firstVariantId = X, firstRemaining = xRemaining, secondVariantId = Y, secondRemaining = yRemaining)

  /** The order after F1's cancel: Shopify put the units on new fulfillment orders. */
  private fun afterSplitCancel(xRemaining: Int, yRemaining: Int) = orderWithTwoVariantFulfillmentOrders(
    firstVariantId = X,
    firstRemaining = xRemaining,
    firstFoId = 303L,
    firstLineItemId = 403L,
    secondVariantId = Y,
    secondRemaining = yRemaining,
    secondFoId = 304L,
    secondLineItemId = 404L,
  )

  /** A shop that serves the split: F1 live before, canceled after the cancel, and two creates that succeed. */
  private fun splitShop(
    reloaded: Order = afterSplitCancel(xRemaining = 2, yRemaining = 1).copy(fulfillments = listOf(canceledF1)),
  ) = FakeShopifyGraphqlService().apply {
    orderForDssResultQueue += Success(beforeSplit().copy(fulfillments = listOf(F1)))
    orderForDssResultQueue += Success(reloaded)
    fulfillmentContentResults[F1.id] = Success(PackageContent.Known(mapOf(X to 2, Y to 1)))
    createFulfillmentResultQueue += Success(ShopifyFulfillmentId(6001L))
    createFulfillmentResultQueue += Success(ShopifyFulfillmentId(6002L))
  }

  private fun splitRequest(
    b: Shipment = shipment(tracking = "T1", variantId = X, quantity = 2),
    c: Shipment = shipment(tracking = "T2", variantId = Y, quantity = 1),
  ) = syncRequest(shipments = listOf(b, c)).copy(retiredTrackingNumbers = listOf("T1"))

  /** The monolith's retry after a stop: Shopify shows F1 canceled and has nothing of B or C yet. */
  private suspend fun retryAfterTheCancel(
    fake: FakeShopifyGraphqlService,
    request: SyncShipmentsWithFulfillmentsRequest = splitRequest(),
  ): ShipmentSyncReport {
    fake.clear()
    fake.orderForDssResult = Success(
      afterSplitCancel(xRemaining = 2, yRemaining = 1).copy(fulfillments = listOf(canceledF1)),
    )
    fake.createFulfillmentResultQueue += Success(ShopifyFulfillmentId(7001L))
    fake.createFulfillmentResultQueue += Success(ShopifyFulfillmentId(7002L))
    return syncShopifyShipmentsToFulfillments(fake, request).successValue()
  }

  /** The request with a shipment ahead of B that never fit: what the priority of a carried number is about. */
  private fun withOlderShipment(request: SyncShipmentsWithFulfillmentsRequest) =
    request.copy(shipments = listOf(shipment(tracking = "T0", variantId = X, quantity = 2)) + request.shipments)

  /**
   * An order with nothing open and two live fulfillments: F1 under the reused T1 holding one X, F2 under the retired
   * T2 holding the other. A shipment of two X under T1 fits only if both are canceled.
   */
  private fun twoFulfillmentShop() = FakeShopifyGraphqlService().apply {
    orderForDssResultQueue += Success(beforeSplit().copy(fulfillments = listOf(F1, F2)))
    orderForDssResultQueue += Success(afterSplitCancel(xRemaining = 2, yRemaining = 0).copy(fulfillments = listOf(canceledF1)))
    fulfillmentContentResults[F1.id] = Success(PackageContent.Known(mapOf(X to 1)))
    fulfillmentContentResults[F2.id] = Success(PackageContent.Known(mapOf(X to 1)))
    createFulfillmentResultQueue += Success(ShopifyFulfillmentId(6001L))
  }


  /** The created ids as the wire carries them; `successValue()` names the failure when the run was not a success. */
  private fun Result<ShipmentSyncReport, StoppedShipmentSync>.createdIds(): List<Long> =
    successValue().createdFulfillmentIds.map { it.value }

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
