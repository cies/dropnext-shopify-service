package dropnext.dss.domain

import dropnext.dss.contract.FetchProductsRequest
import org.junit.jupiter.api.Test


class ValidateFetchProductsRequestTest {

  private fun request(
    shopifySubdomain: String = "acme",
    shopifyProductIds: List<Long> = listOf(501L, 502L),
  ) = FetchProductsRequest(shopifySubdomain = shopifySubdomain, shopifyProductIds = shopifyProductIds)

  private fun messagesOf(validation: RequestValidation): List<String> =
    (validation as RequestValidation.Invalid).messages

  @Test
  fun `a batch within the cap is valid`() {
    assert(validateFetchProductsRequest(request()) == RequestValidation.Valid)
  }

  @Test
  fun `a batch of exactly the cap is valid`() {
    val ids = (1L..MAX_PRODUCTS_PER_FETCH.toLong()).toList()
    assert(validateFetchProductsRequest(request(shopifyProductIds = ids)) == RequestValidation.Valid)
  }

  @Test
  fun `a batch past the cap is refused`() {
    val ids = (1L..(MAX_PRODUCTS_PER_FETCH + 1).toLong()).toList()
    val messages = messagesOf(validateFetchProductsRequest(request(shopifyProductIds = ids)))
    assert(messages == listOf("shopify_product_ids holds more than $MAX_PRODUCTS_PER_FETCH ids"))
  }

  /** A caller with nothing to fetch has a bug; answering an empty batch would hide it. */
  @Test
  fun `an empty batch is refused`() {
    val messages = messagesOf(validateFetchProductsRequest(request(shopifyProductIds = emptyList())))
    assert(messages == listOf("shopify_product_ids is required"))
  }

  @Test
  fun `a blank subdomain is refused`() {
    val messages = messagesOf(validateFetchProductsRequest(request(shopifySubdomain = " ")))
    assert(messages == listOf("shopify_subdomain is required"))
  }

  @Test
  fun `a non-positive product id is refused`() {
    val messages = messagesOf(validateFetchProductsRequest(request(shopifyProductIds = listOf(501L, 0L))))
    assert(messages == listOf("invalid shopify_product_ids: every id must be positive"))
  }
}
