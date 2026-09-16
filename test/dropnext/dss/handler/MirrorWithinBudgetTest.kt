package dropnext.dss.handler

import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.helper.orderToCreateShopifyOrderRequest
import dropnext.dss.workflow.WebhookMirrorOutcome
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test


/**
 * The time policy behind one webhook delivery, on virtual time: every `delay` here is skipped by the test scheduler,
 * so the cases cost nothing and `currentTime` is an exact assertion about how long the policy *would* have waited.
 * Through the handler these same facts can only be had by waiting real seconds out, which is both slow and a race.
 *
 * The end-to-end wiring — that a timed-out delivery becomes a `502` Shopify redelivers — stays in
 * `ShopifyWebhookHandlersTest`.
 */
// `testScheduler`, the virtual clock these cases read, is still opt-in. Nothing else here is, and the alternative
// is asserting the outcome without asserting how long the policy waited for it, which is the half that matters.
@OptIn(ExperimentalCoroutinesApi::class)
class MirrorWithinBudgetTest {

  @Test
  fun `work that finishes inside the budget answers what it produced`() = runTest {
    val outcome = mirrorWithinBudget(FakeMonolithService(), budget = 4.seconds, writeGrace = 700.milliseconds) {
      delay(1.seconds)
      WebhookMirrorOutcome.Mirrored
    }

    assert(outcome == WebhookMirrorOutcome.Mirrored)
    assert(testScheduler.currentTime == 1.seconds.inWholeMilliseconds)
  }

  /** Nothing has changed yet, so there is nothing to protect and no reason to hold the delivery open any longer. */
  @Test
  fun `work that never wrote to the monolith is cancelled at the budget, with no grace at all`() = runTest {
    var cancelled = false
    val outcome = mirrorWithinBudget(FakeMonolithService(), budget = 4.seconds, writeGrace = 1.hours) {
      try {
        delay(1.hours)
        WebhookMirrorOutcome.Mirrored
      } catch (e: CancellationException) {
        cancelled = true
        throw e
      }
    }

    assert(outcome == WebhookMirrorOutcome.TimedOut)
    assert(cancelled)
    assert(testScheduler.currentTime == 4.seconds.inWholeMilliseconds)
  }

  /** Cancelling a write already on the wire would discard a commit the monolith may just have made. */
  @Test
  fun `a write already sent when the budget ends is given its grace and still counts`() = runTest {
    val monolith = FakeMonolithService().apply { writeDelay = 1.seconds }

    val outcome = mirrorWithinBudget(monolith, budget = 500.milliseconds, writeGrace = 2.seconds) { tracked ->
      tracked.postCreateOrder(orderToCreateShopifyOrderRequest("acme", minimalOrder()))
      WebhookMirrorOutcome.Mirrored
    }

    // The fake records a write before it waits, so only the outcome and the clock show that the write was waited out.
    assert(outcome == WebhookMirrorOutcome.Mirrored)
    assert(testScheduler.currentTime == 1.seconds.inWholeMilliseconds)
  }

  /** The grace bounds the wait: past it the redelivery finds the work done or does it again. */
  @Test
  fun `a write that outlives its grace too is cancelled`() = runTest {
    val monolith = FakeMonolithService().apply { writeDelay = 1.hours }

    val outcome = mirrorWithinBudget(monolith, budget = 500.milliseconds, writeGrace = 300.milliseconds) { tracked ->
      tracked.postCreateOrder(orderToCreateShopifyOrderRequest("acme", minimalOrder()))
      WebhookMirrorOutcome.Mirrored
    }

    assert(outcome == WebhookMirrorOutcome.TimedOut)
    assert(testScheduler.currentTime == 800L)
  }

  /** A delete writes without reading first, so its grace has to start from the first call it makes. */
  @Test
  fun `a variant delete counts as a write too`() = runTest {
    val monolith = FakeMonolithService().apply { writeDelay = 1.seconds }

    val outcome = mirrorWithinBudget(monolith, budget = 500.milliseconds, writeGrace = 2.seconds) { tracked ->
      tracked.deleteProductVariants(DeleteProductVariantsRequest(shopifySubdomain = "acme", productId = 503L))
      WebhookMirrorOutcome.Mirrored
    }

    assert(outcome == WebhookMirrorOutcome.Mirrored)
    assert(testScheduler.currentTime == 1.seconds.inWholeMilliseconds)
  }

  /**
   * The token lookup behind a delivery goes to the monolith through the same interface. Counting it as a write would
   * hand every delivery the grace, including the reads the grace exists to *not* protect.
   */
  @Test
  fun `a read of the monolith does not count as a write`() = runTest {
    val monolith = FakeMonolithService()

    val outcome = mirrorWithinBudget(monolith, budget = 500.milliseconds, writeGrace = 1.hours) { tracked ->
      tracked.getStore("acme")
      delay(1.hours)
      WebhookMirrorOutcome.Mirrored
    }

    assert(outcome == WebhookMirrorOutcome.TimedOut)
    assert(monolith.getStoreCalls.single() == "acme")
    assert(testScheduler.currentTime == 500.milliseconds.inWholeMilliseconds)
  }
}
