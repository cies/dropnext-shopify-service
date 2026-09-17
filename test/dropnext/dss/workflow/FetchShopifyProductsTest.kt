package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.sampleProduct
import dropnext.dss.testutil.fixture.sampleProductPage
import dropnext.dss.testutil.fixture.sampleProductVariant
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


class FetchShopifyProductsTest {

  private val budget = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 940.0, restoreRate = 50.0)

  /** Records what the fetch would have waited, so no test waits. */
  private val pauses = mutableListOf<Duration>()
  private val recordPause: suspend (Duration) -> Unit = { pauses.add(it) }

  /**
   * The fake answers one result for every product, so a case that needs a different answer per product wraps it and
   * routes by gid. Each product's answers are served in turn, the last one for every later call. Kept here: no other
   * test needs per-product answers.
   */
  private class PerProductShopify(
    private val delegate: FakeShopifyGraphqlService,
    answers: Map<String, List<ShopifyResult<ShopProduct?>>>,
  ) : ShopifyGraphqlService by delegate {
    private val queues = answers.mapValues { (_, results) -> results.toMutableList() }

    override suspend fun productById(productGid: String, variantsAfter: String?): ShopifyResult<ShopProduct?> {
      delegate.productById(productGid, variantsAfter)
      val queue = queues.getValue(productGid)
      return if (queue.size > 1) queue.removeAt(0) else queue.single()
    }
  }

  private fun perProduct(fake: FakeShopifyGraphqlService, vararg answers: Pair<Long, ShopifyResult<ShopProduct?>>) =
    PerProductShopify(fake, answers.toList().groupBy({ "gid://shopify/Product/${it.first}" }, { it.second }))

  private fun ids(vararg productIds: Long) = productIds.map(::ShopifyProductId)

  private val throttledOnAHalfFullBucket = ShopifyError.GraphqlError(
    "Throttled",
    codes = listOf("THROTTLED"),
    rateBudget = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 600.0, restoreRate = 50.0),
    requestedCost = 800,
  )

  private fun product(id: Long, vararg variantIds: Long): ShopProduct =
    ShopProduct(
      product = sampleProduct(legacyResourceId = id.toString(), variants = variantIds.map { sampleProductVariant(it.toString()) }),
      shopCurrencyCode = "EUR",
      rateBudget = budget,
    )

  @Test
  fun `each product is loaded and mapped the way a product webhook maps it`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val shopify = perProduct(fake, 501L to Success(product(501, 11, 12)), 502L to Success(product(502, 21)))

    val fetched = fetchShopifyProducts(shopify, ids(501, 502), pause = recordPause).successValue()

    assert(fake.productByIdCalls == listOf("gid://shopify/Product/501", "gid://shopify/Product/502"))
    assert(fetched.productVariants.map { it.productVariantId } == listOf(11L, 12L, 21L))
    assert(fetched.productVariants.all { it.priceCurrency == "EUR" })
    assert(fetched.missingProductIds.isEmpty())
    assert(fetched.unfetchedProductIds.isEmpty())
    // The bucket was well above half, so there is nothing to wait for.
    assert(fetched.nextRequestAfter == Duration.ZERO)
    assert(pauses.isEmpty())
  }

  /** Not an error: it is how a product deleted between the catalog read and this call comes back. */
  @Test
  fun `a product Shopify no longer has is reported missing and the others still answer`() = runBlocking {
    val shopify = perProduct(FakeShopifyGraphqlService(), 501L to Success(null), 502L to Success(product(502, 21)))

    val fetched = fetchShopifyProducts(shopify, ids(501, 502), pause = recordPause).successValue()

    assert(fetched.missingProductIds == listOf(ShopifyProductId(501)))
    assert(fetched.productVariants.map { it.productVariantId } == listOf(21L))
  }

  /**
   * A product that keeps failing ends the fetch, but not the products already loaded: they are answered, and the rest
   * come back as not fetched, so the monolith stores what it can and asks for the rest again. Nothing is answered for a
   * product that did not load whole.
   */
  @Test
  fun `a failure on a later product answers the products before it and leaves the rest unfetched`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val shopify = perProduct(
      fake,
      501L to Success(product(501, 11)),
      502L to Failure(ShopifyError.Network("reset")),
      503L to Success(product(503, 31)),
    )

    val fetched = fetchShopifyProducts(shopify, ids(501, 502, 503), pause = recordPause).successValue()

    assert(fetched.productVariants.map { it.productVariantId } == listOf(11L))
    assert(fetched.missingProductIds.isEmpty())
    assert(fetched.unfetchedProductIds == ids(502, 503))
    // The failing product was asked for again as often as the retries allow; the third was never asked for.
    val gids = listOf("gid://shopify/Product/501") + List(FETCH_PAGE_RETRIES + 1) { "gid://shopify/Product/502" }
    assert(fake.productByIdCalls == gids)
    assert(pauses == listOf(1.seconds, 2.seconds))
  }

  /**
   * The case the fetch was changed for: a batch that drains the shop's bucket part-way. The products loaded before the
   * throttle are answered, not thrown away, and the wait handed back covers the refused query's own cost, so the
   * monolith's next call is not refused in turn. 600 of 1,000 points is above the floor, and the 800-point query needs
   * 200 more at 50 a second.
   */
  @Test
  fun `a batch throttled part-way answers what it loaded and how long to wait before the rest`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val shopify = perProduct(
      fake,
      501L to Success(product(501, 11, 12)),
      502L to Failure(throttledOnAHalfFullBucket),
      503L to Success(product(503, 31)),
    )

    val fetched = fetchShopifyProducts(shopify, ids(501, 502, 503), pause = recordPause).successValue()

    assert(fetched.productVariants.map { it.productVariantId } == listOf(11L, 12L))
    assert(fetched.unfetchedProductIds == ids(502, 503))
    assert(fetched.nextRequestAfter == 4.seconds)
    // Each retry waited for the query's cost, which is longer than the backoff.
    assert(pauses == listOf(4.seconds, 4.seconds))
    assert(fake.productByIdCalls.count { it == "gid://shopify/Product/503" } == 0)
  }

  @Test
  fun `a throttled product that loads on a retry is answered with the rest`() = runBlocking {
    val shopify = perProduct(
      FakeShopifyGraphqlService(),
      501L to Failure(throttledOnAHalfFullBucket),
      501L to Success(product(501, 11)),
      502L to Success(product(502, 21)),
    )

    val fetched = fetchShopifyProducts(shopify, ids(501, 502), pause = recordPause).successValue()

    assert(fetched.productVariants.map { it.productVariantId } == listOf(11L, 21L))
    assert(fetched.unfetchedProductIds.isEmpty())
    assert(pauses == listOf(4.seconds))
  }

  /** The shop's live webhooks draw on the same bucket: the fetch waits for the floor rather than drain it. */
  @Test
  fun `a bucket left under half by one product is refilled before the next is loaded`() = runBlocking {
    val drained = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 300.0, restoreRate = 50.0)
    val shopify = perProduct(
      FakeShopifyGraphqlService(),
      501L to Success(product(501, 11).copy(rateBudget = drained)),
      502L to Success(product(502, 21).copy(rateBudget = drained)),
    )

    val fetched = fetchShopifyProducts(shopify, ids(501, 502), pause = recordPause).successValue()

    // 200 points short of half, at 50 a second, before the second product; the last one leaves the wait to the caller.
    assert(pauses == listOf(4.seconds))
    assert(fetched.nextRequestAfter == 4.seconds)
  }

  /** Only a fetch that got nowhere fails, so the caller hears why: here, the throttle and its wait. */
  @Test
  fun `a throttle on the first product fails the fetch`() = runBlocking {
    val shopify = perProduct(FakeShopifyGraphqlService(), 501L to Failure(throttledOnAHalfFullBucket))

    val result = fetchShopifyProducts(shopify, ids(501, 502), pause = recordPause)

    assert(result.failureReason() == throttledOnAHalfFullBucket)
  }

  /** The deadline cuts a product off mid-load: it is not answered, and neither is anything after it. */
  @Test
  fun `a fetch past its deadline answers the products it loaded before it`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val stallingOnTheSecond = object : ShopifyGraphqlService by fake {
      override suspend fun productById(productGid: String, variantsAfter: String?): ShopifyResult<ShopProduct?> {
        if (productGid == "gid://shopify/Product/502") awaitCancellation()
        return Success(product(501, 11))
      }
    }

    val fetched = fetchShopifyProducts(stallingOnTheSecond, ids(501, 502, 503), deadline = 50.milliseconds, pause = recordPause)
      .successValue()

    assert(fetched.productVariants.map { it.productVariantId } == listOf(11L))
    assert(fetched.unfetchedProductIds == ids(502, 503))
  }

  /** A product this service cannot load is not the monolith's to retry blindly, but the ones before it still count. */
  @Test
  fun `a failure no retry fixes after a loaded product still answers that product`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val shopify = perProduct(
      fake,
      501L to Success(product(501, 11)),
      502L to Failure(ShopifyError.Truncated("product.variants", 2500)),
    )

    val fetched = fetchShopifyProducts(shopify, ids(501, 502), pause = recordPause).successValue()

    assert(fetched.productVariants.map { it.productVariantId } == listOf(11L))
    assert(fetched.unfetchedProductIds == ids(502))
    assert(fake.productByIdCalls == listOf("gid://shopify/Product/501", "gid://shopify/Product/502"))
    assert(pauses.isEmpty())
  }

  @Test
  fun `a product named twice is loaded once and answered once`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { productByIdResult = Success(product(501, 11)) }

    val fetched = fetchShopifyProducts(fake, ids(501, 501), pause = recordPause).successValue()

    assert(fake.productByIdCalls.size == 1)
    assert(fetched.productVariants.size == 1)
  }

  /**
   * A variant list is complete or the call fails: a product past the page size is refused by the service, so the batch
   * fails rather than answering a product short of variants the monolith would then prune.
   */
  @Test
  fun `a truncated product fails the batch`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      productByIdResult = Failure(ShopifyError.Truncated("product.variants", 100))
    }

    val result = fetchShopifyProducts(fake, ids(501), pause = recordPause)

    assert(result.failureReason() == ShopifyError.Truncated("product.variants", 100))
  }

  @Test
  fun `a product with more variants than a page answers all of them`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage((1L..100L).toList(), nextCursor = "c1"))
      productByIdResultQueue += Success(sampleProductPage((101L..150L).toList()))
    }

    val fetched = fetchShopifyProducts(fake, ids(501), pause = recordPause).successValue()

    assert(fetched.productVariants.map { it.productVariantId } == (1L..150L).toList())
    assert(fake.productByIdCursors == listOf(null, "c1"))
  }

  @Test
  fun `a product removed between its pages is missing`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage(listOf(1), nextCursor = "c1"))
      productByIdResultQueue += Success(null)
    }

    val fetched = fetchShopifyProducts(fake, ids(501), pause = recordPause).successValue()

    assert(fetched.productVariants.isEmpty())
    assert(fetched.missingProductIds == listOf(ShopifyProductId(501)))
  }

  /** The monolith has stopped reading by then; the answer says so in a way it retries. */
  @Test
  fun `a fetch that outlives its deadline answers a retryable time-out`() = runBlocking {
    val stalled = object : ShopifyGraphqlService by FakeShopifyGraphqlService() {
      override suspend fun productById(productGid: String, variantsAfter: String?): ShopifyResult<ShopProduct?> =
        awaitCancellation()
    }

    val reason = fetchShopifyProducts(stalled, ids(501), deadline = 50.milliseconds, pause = recordPause).failureReason()

    assert(reason == ShopifyError.TimedOut("fetching 1 products", 50.milliseconds))
    assert(reason.isRetryable)
  }

  @Test
  fun `the deadline is under the thirty seconds the monolith waits`() {
    assert(FETCH_PRODUCTS_BUDGET < 30.seconds)
  }

  /** Impossible in Shopify, and answered as an empty item list rather than as missing if it ever happens. */
  @Test
  fun `a product without variants answers no items and is not missing`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { productByIdResult = Success(product(501)) }

    val fetched = fetchShopifyProducts(fake, ids(501), pause = recordPause).successValue()

    assert(fetched.productVariants.isEmpty())
    assert(fetched.missingProductIds.isEmpty())
  }
}
