package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.lib.shopify.graphql.ShopifyCatalogEntry
import dropnext.dss.lib.shopify.graphql.ShopifyCatalogPage
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


class ReadShopifyCatalogTest {

  /** Records what the walk would have waited, so no test waits. */
  private val pauses = mutableListOf<Duration>()
  private val recordPause: suspend (Duration) -> Unit = { pauses.add(it) }

  private fun entry(productId: Long, variantId: Long) =
    ShopifyCatalogEntry(ShopifyProductId(productId), ShopifyVariantId(variantId))

  private fun page(vararg entries: ShopifyCatalogEntry, next: String? = null, budget: ShopifyRateBudget? = null) =
    Success(ShopifyCatalogPage(entries = entries.toList(), nextCursor = next, rateBudget = budget))

  private val fullBucket = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 1000.0, restoreRate = 50.0)

  @Test
  fun `the walk follows the cursor to the end and groups variants under their products`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), entry(1, 12), next = "c1")
      productVariantIdsPageResultQueue += page(entry(2, 21), next = "c2")
      productVariantIdsPageResultQueue += page(entry(3, 31))
    }

    val catalog = readShopifyCatalog(shopify, pageSize = 2, pause = recordPause).successValue()

    assert(shopify.productVariantIdsPageCalls.map { it.after } == listOf(null, "c1", "c2"))
    assert(shopify.productVariantIdsPageCalls.all { it.first == 2 })
    assert(catalog.products.map { it.productId.value } == listOf(1L, 2L, 3L))
    assert(catalog.products.first().productVariantIds.map { it.value } == listOf(11L, 12L))
    assert(catalog.productVariantCount == 4)
  }

  /** Paging is by variant, so a product's variants can land on two pages; the answer names that product once. */
  @Test
  fun `a product whose variants straddle a page boundary is one entry`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), entry(1, 12), next = "c1")
      productVariantIdsPageResultQueue += page(entry(1, 13), entry(2, 21))
    }

    val catalog = readShopifyCatalog(shopify, pause = recordPause).successValue()

    assert(catalog.products.size == 2)
    assert(catalog.products.single { it.productId.value == 1L }.productVariantIds.map { it.value } == listOf(11L, 12L, 13L))
  }

  @Test
  fun `an empty shop is an empty catalog, not a failure`() = runBlocking {
    val shopify = FakeShopifyGraphqlService()

    val catalog = readShopifyCatalog(shopify, pause = recordPause).successValue()

    assert(catalog.products.isEmpty())
    assert(catalog.productVariantCount == 0)
    assert(shopify.productVariantIdsPageCalls.size == 1)
  }

  /**
   * The monolith soft-deletes everything the answer leaves out, so a walk that fails half way must not answer the half
   * it has: that would empty the store of every product it never reached.
   */
  @Test
  fun `a failure part way through fails the whole walk`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1")
      productVariantIdsPageResultQueue += Failure(ShopifyError.TokenRejected(401))
    }

    val result = readShopifyCatalog(shopify, pause = recordPause)

    assert(result.failureReason() == ShopifyError.TokenRejected(401))
  }

  @Test
  fun `a throttled page is waited out and asked for again`() = runBlocking {
    val throttled = Failure(ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED")))
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1")
      productVariantIdsPageResultQueue += throttled
      productVariantIdsPageResultQueue += page(entry(2, 21))
    }

    val catalog = readShopifyCatalog(shopify, pause = recordPause).successValue()

    assert(catalog.products.size == 2)
    // The retry carried the same cursor: the page was asked for again, not skipped.
    assert(shopify.productVariantIdsPageCalls.map { it.after } == listOf(null, "c1", "c1"))
    assert(pauses == listOf(1.seconds))
  }

  /** A dropped connection on page 150 of 200 should not throw away the 149 already read. */
  @Test
  fun `a page lost to the network is asked for again too`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1")
      productVariantIdsPageResultQueue += Failure(ShopifyError.Network("connection reset"))
      productVariantIdsPageResultQueue += page(entry(2, 21))
    }

    val catalog = readShopifyCatalog(shopify, pause = recordPause).successValue()

    assert(catalog.products.size == 2)
  }

  @Test
  fun `a page that keeps being throttled fails the walk after the retries run out`() = runBlocking {
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))
    val shopify = FakeShopifyGraphqlService().apply { productVariantIdsPageResult = Failure(throttled) }

    val result = readShopifyCatalog(shopify, pageRetries = 2, pause = recordPause)

    assert(result.failureReason() == throttled)
    assert(shopify.productVariantIdsPageCalls.size == 3)
    // The backoff grows with the attempt, so a bucket that is still empty gets longer to refill each time.
    assert(pauses == listOf(1.seconds, 2.seconds))
  }

  /** A refusal reads the same on every attempt, so asking again only spends the bucket. */
  @Test
  fun `a failure no retry can fix is not retried`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply { productVariantIdsPageResult = Failure(ShopifyError.TokenRejected(401)) }

    readShopifyCatalog(shopify, pause = recordPause)

    assert(shopify.productVariantIdsPageCalls.size == 1)
    assert(pauses.isEmpty())
  }

  @Test
  fun `a bucket under the floor makes the walk wait for the refill before the next page`() = runBlocking {
    val low = fullBucket.copy(currentlyAvailable = 200.0)
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1", budget = low)
      productVariantIdsPageResultQueue += page(entry(2, 21), budget = fullBucket)
    }

    val catalog = readShopifyCatalog(shopify, budgetFloor = 0.5, pause = recordPause).successValue()

    // 500 - 200 = 300 points short of the floor, at 50 a second.
    assert(pauses == listOf(6.seconds))
    assert(catalog.rateBudget == fullBucket)
  }

  @Test
  fun `a bucket above the floor never makes the walk wait`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1", budget = fullBucket)
      productVariantIdsPageResultQueue += page(entry(2, 21), budget = fullBucket)
    }

    readShopifyCatalog(shopify, pause = recordPause)

    assert(pauses.isEmpty())
  }

  /** The last page ends the walk, so there is no next request for a low bucket to protect. */
  @Test
  fun `a low bucket on the last page does not make the walk wait`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResult = page(entry(1, 11), budget = fullBucket.copy(currentlyAvailable = 0.0))
    }

    readShopifyCatalog(shopify, pause = recordPause)

    assert(pauses.isEmpty())
  }

  /** A catalog past the backstop is refused rather than answered in part, and not retried: it is as large next time. */
  @Test
  fun `the walk gives up at the page limit instead of answering a partial catalog`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1")
      productVariantIdsPageResultQueue += page(entry(2, 21), next = "c2")
      productVariantIdsPageResultQueue += page(entry(3, 31), next = "c3")
    }

    val result = readShopifyCatalog(shopify, pageSize = 250, maxPages = 3, pause = recordPause)

    assert(result.failureReason() == ShopifyError.Truncated("productVariants", 750))
    assert(!result.failureReason().isRetryable)
    assert(shopify.productVariantIdsPageCalls.size == 3)
  }

  /** A cursor that does not advance would read the same page until the backstop; it is caught the first time. */
  @Test
  fun `a cursor Shopify hands back unchanged fails the walk at once`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply { productVariantIdsPageResult = page(entry(1, 11), next = "again") }

    val result = readShopifyCatalog(shopify, pause = recordPause)

    assert(result.failureReason() == ShopifyError.GraphqlError("Shopify answered the cursor it was given as the next one"))
    // The page after the first carried the cursor and got it back: two calls, not two hundred.
    assert(shopify.productVariantIdsPageCalls.map { it.after } == listOf(null, "again"))
  }

  /**
   * The caller stops waiting after a while; a walk that went on would spend the shop's budget on an answer nobody reads.
   * The pause here really waits, so the deadline is what ends it.
   */
  @Test
  fun `a walk that outlives its deadline stops and says so in a way the caller retries`() = runBlocking {
    val low = fullBucket.copy(currentlyAvailable = 0.0)
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1", budget = low)
      productVariantIdsPageResultQueue += page(entry(2, 21))
    }

    val result = readShopifyCatalog(shopify, deadline = 50.milliseconds, pause = { delay(it) })

    assert(result.failureReason() == ShopifyError.TimedOut("reading the shop's catalog", 50.milliseconds))
    assert(result.failureReason().isRetryable)
    // It stopped while waiting for the refill: the second page was never asked for.
    assert(shopify.productVariantIdsPageCalls.size == 1)
  }

  /** Waiting less than the refill Shopify reported would only be throttled again. */
  @Test
  fun `a throttled page waits for the refill its own answer reports`() = runBlocking {
    val empty = fullBucket.copy(currentlyAvailable = 0.0)
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"), rateBudget = empty)
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += Failure(throttled)
      productVariantIdsPageResultQueue += page(entry(1, 11))
    }

    readShopifyCatalog(shopify, budgetFloor = 0.5, pause = recordPause).successValue()

    // 500 points short of half the bucket, at 50 a second — longer than the one-second backoff.
    assert(pauses == listOf(10.seconds))
  }

  @Test
  fun `a throttled page that reports no budget waits for the refill the last page reported`() = runBlocking {
    val low = fullBucket.copy(currentlyAvailable = 400.0)
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))
    val shopify = FakeShopifyGraphqlService().apply {
      // Above the floor, so no pacing wait after this page: only the retry waits.
      productVariantIdsPageResultQueue += page(entry(1, 11), next = "c1", budget = fullBucket)
      productVariantIdsPageResultQueue += page(entry(2, 21), next = "c2", budget = low)
      productVariantIdsPageResultQueue += Failure(throttled)
      productVariantIdsPageResultQueue += page(entry(3, 31))
    }

    readShopifyCatalog(shopify, budgetFloor = 0.5, pause = recordPause).successValue()

    // The pacing wait after page two, then the retry's: both the refill of 100 points at 50 a second.
    assert(pauses == listOf(2.seconds, 2.seconds))
  }

  /** A refill already done leaves the backoff: a retryable failure is never asked again at once. */
  @Test
  fun `a throttled page whose bucket is already refilled still waits the backoff`() = runBlocking {
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"), rateBudget = fullBucket)
    val shopify = FakeShopifyGraphqlService().apply {
      productVariantIdsPageResultQueue += Failure(throttled)
      productVariantIdsPageResultQueue += page(entry(1, 11))
    }

    readShopifyCatalog(shopify, pause = recordPause).successValue()

    assert(pauses == listOf(1.seconds))
  }

  @Test
  fun `a shop without a cost block still reads, with no budget and no waiting`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply { productVariantIdsPageResult = page(entry(1, 11)) }

    val catalog = readShopifyCatalog(shopify, pause = recordPause).successValue()

    assert(catalog.rateBudget == null)
    assert(pauses.isEmpty())
  }
}
