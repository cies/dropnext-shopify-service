package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.CancelReason
import dropnext.dss.domain.fulfillment.PackageContent
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.orderWithFoQuantities
import dropnext.dss.testutil.fixture.shipment
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.MutableTimeSource
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import dropnext.graphql.generated.getorderfordss.Fulfillment
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


/** Which contents are loaded, and how a failed load is read. What the plan decides is `PlanCancelsTest`'s. */
class DetermineFulfillmentCancelsTest {

  private val orderId = ShopifyOrderId(1001L)
  private val f1 = fulfillment(8001L, listOf("TRK-1"))
  private val f2 = fulfillment(8002L, listOf("TRK-2"))

  private fun serving(vararg fulfillments: Fulfillment) = FakeShopifyGraphqlService().apply {
    orderForDssResult = Success(orderWithFoQuantities(remaining = 2).copy(fulfillments = fulfillments.toList()))
  }

  /** Only a reused number needs a comparison, so a run of new shipments costs the order load alone. */
  @Test
  fun `a run without a reused number loads the order once and no content`() = runBlocking {
    val fake = serving(f2)

    val decision = determineFulfillmentCancels(fake, orderId, listOf(shipment(tracking = "TRK-1")), listOf("TRK-2"), ShipmentSyncBudget.unlimited())

    assert(fake.orderForDssCalls == listOf("gid://shopify/Order/1001"))
    assert(fake.fulfillmentContentCalls.isEmpty())
    assert(decision.successValue().retiredCancels == listOf(ShopifyMutation.FulfillmentCancel(ShopifyFulfillmentId(8002L), "TRK-2")))
    assert(fake.cancelFulfillmentCalls.isEmpty())
  }

  @Test
  fun `a reused number loads the content of its fulfillment and compares it`() = runBlocking {
    val fake = serving(f1).apply { fulfillmentContentResults[f1.id] = Success(PackageContent.Known(mapOf(101L to 2))) }

    val decision = determineFulfillmentCancels(fake, orderId, listOf(shipment(tracking = "TRK-1", quantity = 1)), listOf("TRK-1"), ShipmentSyncBudget.unlimited())

    assert(fake.fulfillmentContentCalls == listOf(f1.id))
    val plan = decision.successValue().plan
    assert(plan.cancels.map { it.fulfillment.id to it.reason } == listOf(f1.id to CancelReason.CONTENT_CHANGED))
    assert(decision.successValue().contentChangedCancels.map { it.trackingNumber } == listOf("TRK-1"))
  }

  /** More lines than one page cannot be compared; the fit check then decides whether the fulfillment is replaced. */
  @Test
  fun `a truncated content is compared as unknown`() = runBlocking {
    val fake = serving(f1).apply {
      fulfillmentContentResults[f1.id] = Failure(ShopifyError.Truncated("fulfillment.fulfillmentLineItems", 100))
    }

    val decision = determineFulfillmentCancels(fake, orderId, listOf(shipment(tracking = "TRK-1")), listOf("TRK-1"), ShipmentSyncBudget.unlimited())

    assert(decision.successValue().plan.cancels.map { it.reason } == listOf(CancelReason.CONTENT_CHANGED))
  }

  @Test
  fun `a fulfillment gone before its content was loaded counts as canceled`() = runBlocking {
    val fake = serving(f1).apply { fulfillmentContentResults[f1.id] = Failure(ShopifyError.NotFound("fulfillment 8001 not found")) }

    val decision = determineFulfillmentCancels(fake, orderId, listOf(shipment(tracking = "TRK-1")), listOf("TRK-1"), ShipmentSyncBudget.unlimited())

    assert(decision.successValue().goneFulfillmentIds == setOf(f1.id))
    assert(decision.successValue().plan.cancels.isEmpty())
  }

  @Test
  fun `any other failure of a content load stops the run`() = runBlocking {
    val fake = serving(f1).apply { fulfillmentContentResults[f1.id] = Failure(ShopifyError.HttpError(503)) }

    val decision = determineFulfillmentCancels(fake, orderId, listOf(shipment(tracking = "TRK-1")), listOf("TRK-1"), ShipmentSyncBudget.unlimited())

    assert(decision.failureReason() == ShopifyError.HttpError(503))
  }

  @Test
  fun `a failed order load is passed through`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { orderForDssResult = Failure(ShopifyError.NotFound("order 1001 not found")) }

    val decision = determineFulfillmentCancels(fake, orderId, emptyList(), listOf("TRK-1"), ShipmentSyncBudget.unlimited())

    assert(decision == Failure(ShopifyError.NotFound("order 1001 not found")))
  }

  @Test
  fun `a content load the budget would not hold is not started`() = runBlocking {
    val clock = MutableTimeSource()
    val fake = serving(f1).apply {
      shipmentSyncTimeSource = clock
      shipmentSyncCallTakes = 16.seconds
    }
    val budget = ShipmentSyncBudget.startedAt(clock.markNow(), 25.seconds)

    val decision = determineFulfillmentCancels(fake, orderId, listOf(shipment(tracking = "TRK-1")), listOf("TRK-1"), budget)

    assert(decision.failureReason() == ShopifyError.TimedOut("syncing the shipments", 25.seconds))
    assert(fake.fulfillmentContentCalls.isEmpty())
  }

  /** The planned cancels are what an operator looks for when a package disappears from the Shopify admin. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `every decision is logged with its reason`() {
    val shared = fulfillment(8003L, listOf("TRK-3", "TRK-LIVE"))
    val fake = serving(f1, f2, shared).apply {
      fulfillmentContentResults[f1.id] = Success(PackageContent.Known(mapOf(101L to 1)))
      fulfillmentContentResults[f2.id] = Success(PackageContent.Known(mapOf(101L to 1)))
      fulfillmentContentResults[shared.id] = Success(PackageContent.Known(mapOf(101L to 1)))
    }
    val shipments = listOf(shipment(tracking = "TRK-1", quantity = 5), shipment(tracking = "TRK-LIVE"))

    val lines = capturingLogs {
      runBlocking { determineFulfillmentCancels(fake, orderId, shipments, listOf("TRK-1", "TRK-2", "TRK-3"), ShipmentSyncBudget.unlimited()) }
    }

    val decisions = lines.filter { "sync-shipments cancel " in it || "sync-shipments kept " in it }
    assert(decisions.size == 3)
    assert(decisions[0].startsWith("INFO sync-shipments cancel orderId=1001 tracking=TRK-2 fulfillmentId=8002 reason=retired"))
    assert(
      decisions[1].startsWith(
        "WARN sync-shipments kept stale fulfillment orderId=1001 tracking=TRK-1 fulfillmentId=8001 " +
          "reason=replacement_does_not_fit short=[variant=101:asked=5:available=4]",
      ),
    )
    assert(
      decisions[2].startsWith(
        "WARN sync-shipments kept stale fulfillment orderId=1001 tracking=TRK-3 fulfillmentId=8003 " +
          "reason=carries_live_tracking_number liveTracking=[TRK-LIVE]",
      ),
    )
  }

  /** The line a human acts on: the sync will not guess which of the two packages the fulfillment holds. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a fulfillment under two reused numbers is logged as kept, with both numbers`() {
    val both = fulfillment(8001L, listOf("TRK-1", "TRK-2"))
    val fake = serving(both).apply { fulfillmentContentResults[both.id] = Success(PackageContent.Known(mapOf(101L to 9))) }
    val shipments = listOf(shipment(tracking = "TRK-1"), shipment(tracking = "TRK-2", variantId = 202L))

    val lines = capturingLogs {
      runBlocking {
        determineFulfillmentCancels(fake, orderId, shipments, listOf("TRK-1", "TRK-2"), ShipmentSyncBudget.unlimited())
      }
    }

    assert(lines.none { "sync-shipments cancel orderId=" in it })
    val kept = lines.single { "sync-shipments kept stale fulfillment" in it }
    assert(
      kept.startsWith(
        "WARN sync-shipments kept stale fulfillment orderId=1001 tracking=TRK-1 fulfillmentId=8001 " +
          "reason=carries_two_reused_tracking_numbers liveTracking=[TRK-1, TRK-2]",
      ),
    )
  }

  /** The two lines an operator reads when a fulfillment could not be compared; both documented for them. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a content that could not be read is logged with what was wrong`() {
    val truncated = serving(f1).apply {
      fulfillmentContentResults[f1.id] = Failure(ShopifyError.Truncated("fulfillment.fulfillmentLineItems", 100))
    }
    val gone = serving(f1).apply {
      fulfillmentContentResults[f1.id] = Failure(ShopifyError.NotFound("fulfillment 8001 not found"))
    }
    val shipments = listOf(shipment(tracking = "TRK-1"))

    val truncatedLines = capturingLogs {
      runBlocking { determineFulfillmentCancels(truncated, orderId, shipments, listOf("TRK-1"), ShipmentSyncBudget.unlimited()) }
    }
    val goneLines = capturingLogs {
      runBlocking { determineFulfillmentCancels(gone, orderId, shipments, listOf("TRK-1"), ShipmentSyncBudget.unlimited()) }
    }

    val unknown = truncatedLines.single { "sync-shipments content unknown" in it }
    assert(unknown.startsWith("WARN sync-shipments content unknown orderId=1001 fulfillmentId=8001 error=Shopify has more"))
    val missing = goneLines.single { "sync-shipments fulfillment gone" in it }
    assert(missing.startsWith("WARN sync-shipments fulfillment gone orderId=1001 fulfillmentId=8001"))
  }
}
