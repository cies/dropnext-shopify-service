package dropnext.dss.handler

import dev.forkhandles.result4k.Success
import dropnext.dss.contract.DeleteProductVariantsRequest
import dropnext.dss.lib.shopify.graphql.PRODUCT_VARIANTS_PAGE_SIZE
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.sampleProductPage
import dropnext.dss.testutil.helper.orderToCreateShopifyOrderRequest
import dropnext.dss.workflow.WEBHOOK_MAX_VARIANT_PAGES
import dropnext.dss.workflow.WebhookMirrorOutcome
import dropnext.dss.workflow.syncShopifyProductToMonolith
import kotlin.time.Duration
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

  // ---------- a product at the webhook's page cap ----------

  /**
   * A product webhook may load up to [WEBHOOK_MAX_VARIANT_PAGES] pages and then upsert all their variants, inside the
   * same [WEBHOOK_MIRROR_BUDGET]. These cases run the real workflow under the real budget and grace, with latencies
   * that are assumptions, not measurements: a 100-variant `GetProductById` page and a 500-variant upsert, which the
   * monolith writes one row at a time. They show where the budget holds and where a delivery of such a product becomes a
   * `502` that Shopify redelivers, for the same product, every time.
   */
  private fun productAtThePageCap(pageLatency: Duration): FakeShopifyGraphqlService =
    FakeShopifyGraphqlService().apply {
      productByIdDelay = pageLatency
      (0 until WEBHOOK_MAX_VARIANT_PAGES).forEach { page ->
        val variantIds = (page * PRODUCT_VARIANTS_PAGE_SIZE + 1L..(page + 1L) * PRODUCT_VARIANTS_PAGE_SIZE).toList()
        val nextCursor = if (page < WEBHOOK_MAX_VARIANT_PAGES - 1) "c${page + 1}" else null
        productByIdResultQueue += Success(sampleProductPage(variantIds, nextCursor = nextCursor))
      }
    }

  private suspend fun mirrorProductAtThePageCap(shopify: FakeShopifyGraphqlService, monolith: FakeMonolithService) =
    mirrorWithinBudget(monolith, WEBHOOK_MIRROR_BUDGET, WEBHOOK_WRITE_GRACE) { tracked ->
      syncShopifyProductToMonolith(shopify, tracked, "gid://shopify/Product/501")
    }

  @Test
  fun `a product at the page cap fits the budget when Shopify and the monolith answer briskly`() = runTest {
    val shopify = productAtThePageCap(pageLatency = 400.milliseconds)
    val monolith = FakeMonolithService().apply { writeDelay = 1200.milliseconds }

    val outcome = mirrorProductAtThePageCap(shopify, monolith)

    assert(outcome == WebhookMirrorOutcome.Mirrored)
    assert(monolith.upsertProductVariantsCalls.single().productVariants.size == 500)
    // Five pages at 0.4 s and an upsert of 1.2 s: 0.8 s to spare.
    assert(testScheduler.currentTime == 3200L)
  }

  /** The grace is what saves this one: the upsert started inside the budget and lands within the grace. */
  @Test
  fun `a product at the page cap with slower pages still lands when the upsert started inside the budget`() = runTest {
    val shopify = productAtThePageCap(pageLatency = 600.milliseconds)
    val monolith = FakeMonolithService().apply { writeDelay = 1500.milliseconds }

    val outcome = mirrorProductAtThePageCap(shopify, monolith)

    assert(outcome == WebhookMirrorOutcome.Mirrored)
    assert(testScheduler.currentTime == 4500L)
  }

  /** Nothing was written, so nothing is lost; but the redelivery loads the same five slow pages. */
  @Test
  fun `a product at the page cap whose pages outlast the budget is timed out before anything is written`() = runTest {
    val shopify = productAtThePageCap(pageLatency = 850.milliseconds)
    val monolith = FakeMonolithService()

    val outcome = mirrorProductAtThePageCap(shopify, monolith)

    assert(outcome == WebhookMirrorOutcome.TimedOut)
    assert(monolith.upsertProductVariantsCalls.isEmpty())
    assert(testScheduler.currentTime == WEBHOOK_MIRROR_BUDGET.inWholeMilliseconds)
  }

  /**
   * The worst shape: the upsert is on the wire when the grace runs out. The monolith may well commit it, yet Shopify is
   * told `502` and redelivers the whole product.
   */
  @Test
  fun `a product at the page cap whose upsert outlasts the grace is timed out after the write was sent`() = runTest {
    val shopify = productAtThePageCap(pageLatency = 700.milliseconds)
    val monolith = FakeMonolithService().apply { writeDelay = 1500.milliseconds }

    val outcome = mirrorProductAtThePageCap(shopify, monolith)

    assert(outcome == WebhookMirrorOutcome.TimedOut)
    assert(monolith.upsertProductVariantsCalls.single().productVariants.size == 500)
    assert(testScheduler.currentTime == (WEBHOOK_MIRROR_BUDGET + WEBHOOK_WRITE_GRACE).inWholeMilliseconds)
  }
}
