package dropnext.dss.domain

import dropnext.dss.contract.RegisterShopifyWebhooksRequest
import org.junit.jupiter.api.Test


class ValidateRegisterShopifyWebhooksRequestTest {

  @Test
  fun `a request naming a subdomain is valid`() {
    assert(validateRegisterShopifyWebhooksRequest(RegisterShopifyWebhooksRequest("acme")) == RequestValidation.Valid)
  }

  @Test
  fun `a blank shopify_subdomain is refused`() {
    val validation = validateRegisterShopifyWebhooksRequest(RegisterShopifyWebhooksRequest("  "))
    assert((validation as RequestValidation.Invalid).messages == listOf("shopify_subdomain is required"))
  }
}
