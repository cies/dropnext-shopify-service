package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.ProductVariantItem
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.graphql.errorLabel
import dropnext.dss.lib.shopify.productGid
import dropnext.dss.mapper.toProductVariantItems
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull


private val log = KotlinLogging.logger {}

/**
 * How long one fetch may take. The monolith stops reading the answer after 30 seconds, and this route sends nothing
 * until the whole batch is loaded; a fetch that went on past that would spend the shop's rate budget on an answer
 * nobody reads, while the monolith's retry started a second one. So it stops a little earlier, in a way the monolith
 * retries.
 */
val FETCH_PRODUCTS_BUDGET: Duration = 25.seconds

/**
 * The products the monolith named, as the variant items its upsert already takes, plus the ids Shopify no longer has.
 * [rateBudget] is what the last answer reported, so the caller learns what its batch cost without asking again.
 */
data class FetchedShopifyProducts(
  val productVariants: List<ProductVariantItem>,
  val missingProductIds: List<ShopifyProductId>,
  val rateBudget: ShopifyRateBudget?,
)

/**
 * Loads each product with all its variants and maps it the way a `products/update` webhook would, one at a time and in
 * the order given, within [deadline].
 *
 * **Stops at the first failure rather than answering what it has.** A partial answer is worse than none here: the
 * monolith stores what it receives and marks the batch done, so the products after the failure would be recorded as
 * synced without anyone having looked at them. A product Shopify no longer has is not a failure — it comes back under
 * [FetchedShopifyProducts.missingProductIds], and the caller soft-deletes the variants it holds under that id.
 *
 * The fetch logs its outcome once, successful or not, so a handler answering its failure does not log it again.
 */
suspend fun fetchShopifyProducts(
  shopify: ShopifyGraphqlService,
  productIds: List<ShopifyProductId>,
  deadline: Duration = FETCH_PRODUCTS_BUDGET,
): ShopifyResult<FetchedShopifyProducts> {
  // A repeated id is one product and one Shopify load; answering its variants twice would have the monolith upsert
  // them twice for no gain.
  val distinctIds = productIds.distinct()
  val fetched = withTimeoutOrNull(deadline) { fetchInOrder(shopify, distinctIds) }
  if (fetched != null) return fetched

  val reason = ShopifyError.TimedOut("fetching ${distinctIds.size} products", deadline)
  log.warn { "Fetch products failed asked=${distinctIds.size} error=${reason.errorLabel}: ${reason.message}" }
  return Failure(reason)
}

private suspend fun fetchInOrder(
  shopify: ShopifyGraphqlService,
  productIds: List<ShopifyProductId>,
): ShopifyResult<FetchedShopifyProducts> {
  val variantItems = mutableListOf<ProductVariantItem>()
  val missing = mutableListOf<ShopifyProductId>()
  var budget: ShopifyRateBudget? = null

  productIds.forEach { productId ->
    when (val loaded = loadShopifyProduct(shopify, productGid(productId))) {
      is Failure -> {
        val line = "Fetch products failed productId=$productId error=${loaded.reason.errorLabel}: ${loaded.reason.message}"
        // A failure a retry cannot fix needs a human: a revoked token, a product with more variants than we load.
        if (loaded.reason.isRetryable) log.warn { line } else log.error { line }
        return Failure(loaded.reason)
      }
      is Success -> {
        val shopProduct = loaded.value
        if (shopProduct == null) {
          missing.add(productId)
        } else {
          budget = shopProduct.rateBudget ?: budget
          variantItems.addAll(shopProduct.product.toProductVariantItems(shopProduct.shopCurrencyCode))
        }
      }
    }
  }

  log.info { "Fetch products done: asked=${productIds.size} variants=${variantItems.size} missing=${missing.size}" }
  return Success(
    FetchedShopifyProducts(productVariants = variantItems, missingProductIds = missing, rateBudget = budget)
  )
}
