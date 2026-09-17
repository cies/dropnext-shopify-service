package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.lib.shopify.graphql.PRODUCT_VARIANTS_PAGE_SIZE
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult


/**
 * A backstop on the pages one product may take. Shopify allows 2,048 variants per product, which is 21 pages at the
 * current page size; a cursor that never ends would otherwise loop forever.
 */
const val MAX_VARIANT_PAGES: Int = 25

/**
 * A whole product: its first page of variants and every later one, in Shopify's order, or a failure. A successful
 * `null` means Shopify has no such product, also when it disappeared between two pages.
 *
 * **Complete or failed.** Every caller treats the variant list as the whole product, and the monolith prunes what the
 * list leaves out, so a product needing more than [maxPages] answers [ShopifyError.Truncated] rather than the pages it
 * got. Any failure of any page is the answer: asking again starts from the first page, which is harmless for a read.
 *
 * The answer carries the first page's header and the last page's rate budget, the one that is current.
 */
suspend fun loadShopifyProduct(
  shopify: ShopifyGraphqlService,
  productGid: String,
  maxPages: Int = MAX_VARIANT_PAGES,
): ShopifyResult<ShopProduct?> {
  val first = when (val loaded = shopify.productById(productGid)) {
    is Failure -> return loaded
    is Success -> loaded.value ?: return loaded
  }
  if (first.nextVariantsCursor == null) return Success(first)

  val edges = first.product.variants.edges.toMutableList()
  var last = first
  var pages = 1
  while (true) {
    val cursor = last.nextVariantsCursor ?: break
    if (pages >= maxPages) return Failure(ShopifyError.Truncated("product.variants", maxPages * PRODUCT_VARIANTS_PAGE_SIZE))

    val page = when (val loaded = shopify.productById(productGid, variantsAfter = cursor)) {
      is Failure -> return loaded
      is Success -> loaded.value ?: return Success(null)
    }
    pages++
    // Shopify handing back the cursor it was just given would read the same page until the cap, and answer its
    // variants several times over.
    if (page.nextVariantsCursor == cursor) {
      return Failure(ShopifyError.GraphqlError("Shopify answered the variants cursor it was given as the next one"))
    }
    edges += page.product.variants.edges
    last = page
  }

  val variants = first.product.variants.copy(pageInfo = last.product.variants.pageInfo, edges = edges)
  return Success(
    first.copy(
      product = first.product.copy(variants = variants),
      rateBudget = last.rateBudget ?: first.rateBudget,
      nextVariantsCursor = null,
    )
  )
}
