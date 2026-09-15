package dropnext.dss.lib.shopify.webhook

import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import kotlin.test.Test

class ShopifyWebhookTopicTest {

  @Test
  fun `parses each known topic header`() {
    assert(ShopifyWebhookTopic.parse("products/create") == ShopifyWebhookTopic.ProductsCreate)
    assert(ShopifyWebhookTopic.parse("products/update") == ShopifyWebhookTopic.ProductsUpdate)
    assert(ShopifyWebhookTopic.parse("products/delete") == ShopifyWebhookTopic.ProductsDelete)
    assert(ShopifyWebhookTopic.parse("orders/create") == ShopifyWebhookTopic.OrdersCreate)
    assert(ShopifyWebhookTopic.parse("orders/updated") == ShopifyWebhookTopic.OrdersUpdated)
  }

  @Test
  fun `trims whitespace before matching`() {
    assert(ShopifyWebhookTopic.parse("  orders/create  ") == ShopifyWebhookTopic.OrdersCreate)
  }

  @Test
  fun `unknown topic falls into Other and preserves the raw value`() {
    val parsed = ShopifyWebhookTopic.parse("shop/redact")
    assert(parsed is ShopifyWebhookTopic.Other)
    assert((parsed as ShopifyWebhookTopic.Other).raw == "shop/redact")
  }

  @Test
  fun `null and empty headers fall into Other with an empty raw — distinct from a real unknown topic`() {
    val fromNull = ShopifyWebhookTopic.parse(null)
    val fromEmpty = ShopifyWebhookTopic.parse("")
    val fromBlank = ShopifyWebhookTopic.parse("   ")
    assert(fromNull is ShopifyWebhookTopic.Other && fromNull.raw == "")
    assert(fromEmpty is ShopifyWebhookTopic.Other && fromEmpty.raw == "")
    assert(fromBlank is ShopifyWebhookTopic.Other && fromBlank.raw == "")
  }

  @Test
  fun `is case sensitive — uppercase is not equivalent to canonical`() {
    val parsed = ShopifyWebhookTopic.parse("ORDERS/CREATE")
    assert(parsed is ShopifyWebhookTopic.Other)
  }

  /** Shopify reports a full-payload subscription as `[]` and keeps no order of the names. */
  @Test
  fun `a topic's include fields match Shopify's report as a set, with the full payload reported as empty`() {
    assert(ShopifyWebhookTopic.OrdersCreate.matchesIncludeFields(listOf("admin_graphql_api_id", "id")))
    assert(ShopifyWebhookTopic.ProductsCreate.matchesIncludeFields(emptyList()))
  }

  @Test
  fun `other include fields than a topic declares do not match`() {
    assert(!ShopifyWebhookTopic.OrdersCreate.matchesIncludeFields(emptyList()))
    assert(!ShopifyWebhookTopic.OrdersCreate.matchesIncludeFields(listOf("id")))
    assert(!ShopifyWebhookTopic.ProductsUpdate.matchesIncludeFields(listOf("id")))
  }

  @Test
  fun `a subscription matches a topic with the declared fields, no filter and json`() {
    assert(ShopifyWebhookTopic.OrdersCreate.matchesSubscription(webhookSubscriptionStatus(includeFields = listOf("id", "admin_graphql_api_id"))))
    assert(ShopifyWebhookTopic.ProductsCreate.matchesSubscription(webhookSubscriptionStatus(filter = "")))
  }

  /** A filter drops events, and XML is a body the webhook parsers cannot read. */
  @Test
  fun `a subscription with a filter, in xml or with other fields does not match`() {
    assert(!ShopifyWebhookTopic.ProductsCreate.matchesSubscription(webhookSubscriptionStatus(filter = "vendor:Acme")))
    assert(!ShopifyWebhookTopic.ProductsCreate.matchesSubscription(webhookSubscriptionStatus(format = "XML")))
    assert(!ShopifyWebhookTopic.OrdersCreate.matchesSubscription(webhookSubscriptionStatus(includeFields = emptyList())))
  }
}
