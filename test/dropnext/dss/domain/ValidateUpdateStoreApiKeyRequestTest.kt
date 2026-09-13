package dropnext.dss.domain

import dropnext.dss.contract.UpdateStoreApiKeyRequest
import kotlin.test.Test


class ValidateUpdateStoreApiKeyRequestTest {

  private fun request(
    shopifySubdomain: String = "acme",
    shopifyShopId: Long? = 99L,
    apiKey: String = "shpat_test",
  ) = UpdateStoreApiKeyRequest(shopifySubdomain = shopifySubdomain, shopifyShopId = shopifyShopId, apiKey = apiKey)

  private fun messagesOf(validation: RequestValidation): List<String> =
    (validation as RequestValidation.Invalid).messages

  @Test
  fun `a complete request is valid`() {
    assert(validateUpdateStoreApiKeyRequest(request()) == RequestValidation.Valid)
  }

  /** Null is how the DSS says it could not learn the id; the monolith then keeps the one it has. */
  @Test
  fun `a null shopify_shop_id is valid`() {
    assert(validateUpdateStoreApiKeyRequest(request(shopifyShopId = null)) == RequestValidation.Valid)
  }

  @Test
  fun `a blank api_key is refused`() {
    assert(messagesOf(validateUpdateStoreApiKeyRequest(request(apiKey = "   "))) == listOf("api_key is required"))
  }

  @Test
  fun `a blank shopify_subdomain is refused`() {
    assert(messagesOf(validateUpdateStoreApiKeyRequest(request(shopifySubdomain = ""))) == listOf("shopify_subdomain is required"))
  }

  /** Zero was the old sentinel for "unknown"; a caller still sending it has to be told, not silently stored. */
  @Test
  fun `a zero or negative shopify_shop_id is refused`() {
    assert(messagesOf(validateUpdateStoreApiKeyRequest(request(shopifyShopId = 0L))).single().startsWith("invalid shopify_shop_id"))
    assert(messagesOf(validateUpdateStoreApiKeyRequest(request(shopifyShopId = -5L))).single().startsWith("invalid shopify_shop_id"))
  }

  @Test
  fun `every problem is reported together`() {
    val messages = messagesOf(validateUpdateStoreApiKeyRequest(request(shopifySubdomain = " ", shopifyShopId = 0L, apiKey = "")))
    assert(messages.size == 3)
  }
}
