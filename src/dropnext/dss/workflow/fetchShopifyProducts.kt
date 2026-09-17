package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.ProductVariantItem
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.graphql.errorLabel
import dropnext.dss.lib.shopify.graphql.throttledRetryAfter
import dropnext.dss.lib.shopify.productGid
import dropnext.dss.mapper.toProductVariantItems
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull


private val log = KotlinLogging.logger {}

/**
 * How long one fetch may take. The monolith stops reading the answer after 30 seconds, and this route sends nothing
 * until it stops; a fetch that went on past that would spend the shop's rate budget on an answer nobody reads, while the
 * monolith's retry started a second one. So it stops a little earlier and answers what it has.
 */
val FETCH_PRODUCTS_BUDGET: Duration = 25.seconds

/**
 * How often a page of a fetch is waited out and asked for again. Fewer than the catalog walk's: a fetch that keeps being
 * refused answers what it has and lets the monolith come back later, rather than spend its short deadline waiting.
 */
const val FETCH_PAGE_RETRIES: Int = 2

/**
 * The products the monolith named, as the variant items its upsert already takes, the ids Shopify no longer has, and
 * the ids this fetch did not get to. Every product named is in exactly one of those.
 * [nextRequestAfter] is how long the caller should wait before its next call for this shop.
 */
data class FetchedShopifyProducts(
  val productVariants: List<ProductVariantItem>,
  val missingProductIds: List<ShopifyProductId>,
  val unfetchedProductIds: List<ShopifyProductId>,
  val nextRequestAfter: Duration,
)

/**
 * Loads each product with all its variants and maps it the way a `products/update` webhook would, one at a time and in
 * the order given, within [deadline].
 *
 * **Paced, and complete per product.** Every page goes through a [ShopifyReadPacer], so the fetch waits for the shop's
 * bucket rather than draining it under the shop's live webhooks, and asks again for a page that failed in a way a retry
 * can fix. A product is answered only once all its pages are in, so a product is never answered short of variants the
 * monolith would then prune. A product Shopify no longer has is not a failure: it comes back under
 * [FetchedShopifyProducts.missingProductIds], and the caller soft-deletes the variants it holds under that id.
 *
 * **It stops early rather than throw work away.** When a product fails, or the deadline passes, the products already
 * answered are the answer and the rest come back under [FetchedShopifyProducts.unfetchedProductIds], for the monolith to
 * ask for again after [FetchedShopifyProducts.nextRequestAfter]. Only a fetch that answered no product at all fails, so
 * the caller hears why: a throttle, a revoked token, a product this service cannot load.
 *
 * The fetch logs its outcome once, successful or not, so a handler answering its failure does not log it again.
 */
suspend fun fetchShopifyProducts(
  shopify: ShopifyGraphqlService,
  productIds: List<ShopifyProductId>,
  deadline: Duration = FETCH_PRODUCTS_BUDGET,
  budgetFloor: Double = CATALOG_BUDGET_FLOOR,
  pageRetries: Int = FETCH_PAGE_RETRIES,
  pause: suspend (Duration) -> Unit = { delay(it) },
): ShopifyResult<FetchedShopifyProducts> {
  // A repeated id is one product and one Shopify load; answering its variants twice would have the monolith upsert
  // them twice for no gain.
  val distinctIds = productIds.distinct()
  val pacer = ShopifyReadPacer(budgetFloor, pageRetries, pause)
  val fetch = ProductFetch(shopify, pacer)
  val finished = withTimeoutOrNull(deadline) { fetch.run(distinctIds) } != null
  val timedOut = if (finished) null else ShopifyError.TimedOut("fetching ${distinctIds.size} products", deadline)
  val stop = fetch.failure ?: timedOut
  val unfetched = distinctIds.filter { it !in fetch.answered }

  val summary = "asked=${distinctIds.size} variants=${fetch.variantItems.size} missing=${fetch.missing.size} " +
    "unfetched=${unfetched.size} waited_ms=${pacer.waited.inWholeMilliseconds}"
  if (stop == null) {
    log.info { "Fetch products done: $summary" }
  } else {
    val outcome = if (fetch.answered.isEmpty()) "failed" else "stopped early"
    val line = "Fetch products $outcome: $summary stopped_at=${unfetched.firstOrNull()} " +
      "error=${stop.errorLabel}: ${stop.message}"
    // A failure a retry cannot fix needs a human: a revoked token, a product with more variants than we load.
    if (stop.isRetryable) log.warn { line } else log.error { line }
    if (fetch.answered.isEmpty()) return Failure(stop)
  }

  val nextRequestAfter = stop?.throttledRetryAfter(budgetFloor, pacer.budget)
    ?: pacer.budget?.takeIf { it.isBelow(budgetFloor) }?.refillTo(budgetFloor)
    ?: Duration.ZERO
  return Success(
    FetchedShopifyProducts(
      productVariants = fetch.variantItems.toList(),
      missingProductIds = fetch.missing.toList(),
      unfetchedProductIds = unfetched,
      nextRequestAfter = nextRequestAfter,
    )
  )
}

/**
 * The state of one fetch, kept outside the deadline so what it got through is still known when the deadline ends it.
 * A product's variants are added only once it is loaded whole, so a product cut off by the deadline is not answered.
 */
private class ProductFetch(
  private val shopify: ShopifyGraphqlService,
  private val pacer: ShopifyReadPacer,
) {
  val variantItems = mutableListOf<ProductVariantItem>()
  val missing = mutableListOf<ShopifyProductId>()
  val answered = mutableSetOf<ShopifyProductId>()

  /** Why the fetch stopped before the last product, or `null` when it did not. */
  var failure: ShopifyError? = null
    private set

  suspend fun run(productIds: List<ShopifyProductId>) {
    productIds.forEach { productId ->
      when (val loaded = loadShopifyProduct(shopify, productGid(productId), pacer = pacer)) {
        is Failure -> {
          failure = loaded.reason
          return
        }
        is Success -> {
          val shopProduct = loaded.value
          if (shopProduct == null) {
            missing.add(productId)
          } else {
            variantItems.addAll(shopProduct.product.toProductVariantItems(shopProduct.shopCurrencyCode))
          }
          answered.add(productId)
        }
      }
    }
  }
}
