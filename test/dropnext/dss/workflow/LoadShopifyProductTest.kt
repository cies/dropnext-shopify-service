package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.lib.shopify.graphql.PRODUCT_VARIANTS_PAGE_SIZE
import dropnext.dss.lib.shopify.graphql.ShopProduct
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.sampleProductPage
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


private const val PRODUCT_GID = "gid://shopify/Product/501"


class LoadShopifyProductTest {

  private fun variantIdsOf(result: ShopifyResult<ShopProduct?>): List<String> =
    result.successValue()!!.product.variants.edges.map { it.node.legacyResourceId }

  @Test
  fun `a product that fits one page takes one call`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply { productByIdResult = Success(sampleProductPage(listOf(11, 12))) }

    val result = loadShopifyProduct(shopify, PRODUCT_GID)

    assert(variantIdsOf(result) == listOf("11", "12"))
    assert(shopify.productByIdCursors == listOf(null))
  }

  @Test
  fun `later pages are appended in order, each asked for with the cursor the page before answered`() = runBlocking {
    val lastBudget = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 600.0, restoreRate = 50.0)
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage(listOf(11, 12), nextCursor = "c1"))
      productByIdResultQueue += Success(sampleProductPage(listOf(21), nextCursor = "c2"))
      productByIdResultQueue += Success(sampleProductPage(listOf(31), rateBudget = lastBudget))
    }

    val result = loadShopifyProduct(shopify, PRODUCT_GID)

    assert(variantIdsOf(result) == listOf("11", "12", "21", "31"))
    assert(shopify.productByIdCalls == List(3) { PRODUCT_GID })
    assert(shopify.productByIdCursors == listOf(null, "c1", "c2"))
    val product = result.successValue()!!
    assert(product.nextVariantsCursor == null)
    assert(!product.product.variants.pageInfo.hasNextPage)
    // The last page's report is the current one.
    assert(product.rateBudget == lastBudget)
  }

  @Test
  fun `a failure on a later page is the answer`() = runBlocking {
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage(listOf(11), nextCursor = "c1"))
      productByIdResultQueue += Failure(throttled)
    }

    assert(loadShopifyProduct(shopify, PRODUCT_GID).failureReason() == throttled)
  }

  @Test
  fun `a product needing more pages than allowed is truncated after the allowed calls`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      (1..3).forEach { page -> productByIdResultQueue += Success(sampleProductPage(listOf(page.toLong()), nextCursor = "c$page")) }
    }

    val result = loadShopifyProduct(shopify, PRODUCT_GID, maxPages = 3)

    assert(result.failureReason() == ShopifyError.Truncated("product.variants", 3 * PRODUCT_VARIANTS_PAGE_SIZE))
    assert(shopify.productByIdCalls.size == 3)
  }

  @Test
  fun `a product that fits exactly the allowed pages is loaded`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage(listOf(1), nextCursor = "c1"))
      productByIdResultQueue += Success(sampleProductPage(listOf(2)))
    }

    assert(variantIdsOf(loadShopifyProduct(shopify, PRODUCT_GID, maxPages = 2)) == listOf("1", "2"))
  }

  /** Without this the same page would be read until the cap, and its variants answered several times over. */
  @Test
  fun `a cursor that does not advance fails the load`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage(listOf(1), nextCursor = "c1"))
      productByIdResult = Success(sampleProductPage(listOf(2), nextCursor = "c1"))
    }

    val reason = loadShopifyProduct(shopify, PRODUCT_GID).failureReason()

    assert(reason is ShopifyError.GraphqlError)
    assert(shopify.productByIdCalls.size == 2)
  }

  @Test
  fun `a product gone between pages is gone`() = runBlocking {
    val shopify = FakeShopifyGraphqlService().apply {
      productByIdResultQueue += Success(sampleProductPage(listOf(1), nextCursor = "c1"))
      productByIdResultQueue += Success(null)
    }

    assert(loadShopifyProduct(shopify, PRODUCT_GID) == Success(null))
  }

  @Test
  fun `a product Shopify does not have is a successful null after one call`() = runBlocking {
    val shopify = FakeShopifyGraphqlService()

    assert(loadShopifyProduct(shopify, PRODUCT_GID) == Success(null))
    assert(shopify.productByIdCalls.size == 1)
  }
}
