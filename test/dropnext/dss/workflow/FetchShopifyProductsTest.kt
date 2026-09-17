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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


class FetchShopifyProductsTest {

  private val budget = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 940.0, restoreRate = 50.0)

  /**
   * The fake answers one result for every product, so a case that needs a different answer per product wraps it and
   * routes by gid. Kept here: no other test needs per-product answers.
   */
  private class PerProductShopify(
    private val delegate: FakeShopifyGraphqlService,
    private val answers: Map<String, ShopifyResult<ShopProduct?>>,
  ) : ShopifyGraphqlService by delegate {
    override suspend fun productById(productGid: String, variantsAfter: String?): ShopifyResult<ShopProduct?> {
      delegate.productById(productGid, variantsAfter)
      return answers.getValue(productGid)
    }
  }

  private fun product(id: Long, vararg variantIds: Long): ShopProduct =
    ShopProduct(
      product = sampleProduct(legacyResourceId = id.toString(), variants = variantIds.map { sampleProductVariant(it.toString()) }),
      shopCurrencyCode = "EUR",
      rateBudget = budget,
    )

  @Test
  fun `each product is loaded and mapped the way a product webhook maps it`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val shopify = PerProductShopify(
      fake,
      mapOf(
        "gid://shopify/Product/501" to Success(product(501, 11, 12)),
        "gid://shopify/Product/502" to Success(product(502, 21)),
      ),
    )

    val fetched = fetchShopifyProducts(shopify, listOf(ShopifyProductId(501), ShopifyProductId(502))).successValue()

    assert(fake.productByIdCalls == listOf("gid://shopify/Product/501", "gid://shopify/Product/502"))
    assert(fetched.productVariants.map { it.productVariantId } == listOf(11L, 12L, 21L))
    assert(fetched.productVariants.all { it.priceCurrency == "EUR" })
    assert(fetched.missingProductIds.isEmpty())
    assert(fetched.rateBudget == budget)
  }

  /** Not an error: it is how a product deleted between the catalog read and this call comes back. */
  @Test
  fun `a product Shopify no longer has is reported missing and the others still answer`() = runBlocking {
    val shopify = PerProductShopify(
      FakeShopifyGraphqlService(),
      mapOf(
        "gid://shopify/Product/501" to Success(null),
        "gid://shopify/Product/502" to Success(product(502, 21)),
      ),
    )

    val fetched = fetchShopifyProducts(shopify, listOf(ShopifyProductId(501), ShopifyProductId(502))).successValue()

    assert(fetched.missingProductIds == listOf(ShopifyProductId(501)))
    assert(fetched.productVariants.map { it.productVariantId } == listOf(21L))
  }

  /**
   * The monolith counts a batch done once it has stored the answer, so answering the first product's variants after the
   * second failed would record the second as synced when nobody looked at it.
   */
  @Test
  fun `a failure on a later product fails the batch and answers nothing it already loaded`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    val shopify = PerProductShopify(
      fake,
      mapOf(
        "gid://shopify/Product/501" to Success(product(501, 11)),
        "gid://shopify/Product/502" to Failure(ShopifyError.Network("reset")),
        "gid://shopify/Product/503" to Success(product(503, 31)),
      ),
    )

    val result = fetchShopifyProducts(shopify, listOf(501L, 502L, 503L).map(::ShopifyProductId))

    assert(result.failureReason() == ShopifyError.Network("reset"))
    // It stopped at the failure: the third product was never asked for.
    assert(fake.productByIdCalls == listOf("gid://shopify/Product/501", "gid://shopify/Product/502"))
  }

  @Test
  fun `a product named twice is loaded once and answered once`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply { productByIdResult = Success(product(501, 11)) }

    val fetched = fetchShopifyProducts(fake, listOf(ShopifyProductId(501), ShopifyProductId(501))).successValue()

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

    val result = fetchShopifyProducts(fake, listOf(ShopifyProductId(501)))

    assert(result.failureReason() == ShopifyError.Truncated("product.variants", 100))
  }

  @Test
  fun `a product with more variants than a page answers all of them`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage((1L..100L).toList(), nextCursor = "c1"))
      productByIdResultQueue += Success(sampleProductPage((101L..150L).toList()))
    }

    val fetched = fetchShopifyProducts(fake, listOf(ShopifyProductId(501))).successValue()

    assert(fetched.productVariants.map { it.productVariantId } == (1L..150L).toList())
    assert(fake.productByIdCursors == listOf(null, "c1"))
  }

  @Test
  fun `a product removed between its pages is missing`() = runBlocking {
    val fake = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage(listOf(1), nextCursor = "c1"))
      productByIdResultQueue += Success(null)
    }

    val fetched = fetchShopifyProducts(fake, listOf(ShopifyProductId(501))).successValue()

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

    val reason = fetchShopifyProducts(stalled, listOf(ShopifyProductId(501)), deadline = 50.milliseconds).failureReason()

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

    val fetched = fetchShopifyProducts(fake, listOf(ShopifyProductId(501))).successValue()

    assert(fetched.productVariants.isEmpty())
    assert(fetched.missingProductIds.isEmpty())
  }
}
