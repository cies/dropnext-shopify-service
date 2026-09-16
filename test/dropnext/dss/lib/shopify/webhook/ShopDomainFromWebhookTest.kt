package dropnext.dss.lib.shopify.webhook

import org.junit.jupiter.api.Test


class ShopDomainFromWebhookTest {

  @Test
  fun `prefers X-Shopify-Shop-Domain header`() {
    val shop = shopDomainFromWebhook("DropNext-Staging.myshopify.com", "other.myshopify.com")
    assert(shop?.normalizedShopifyHost == "dropnext-staging.myshopify.com")
  }

  @Test
  fun `falls back to webhook body domain when header missing`() {
    val shop = shopDomainFromWebhook(null, """{"domain":"acme.myshopify.com"}""")
    assert(shop?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `returns null when neither source is valid`() {
    assert(shopDomainFromWebhook(null, null) == null)
    assert(shopDomainFromWebhook("", "!!!") == null)
  }
}
