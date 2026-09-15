package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import kotlin.test.Test


private const val CALLBACK_URL = "https://dss.example.com/webhooks/shopify"

private fun subscription(id: Int, topic: String, uri: String = CALLBACK_URL) =
  WebhookSubscriptionStatus(id = "gid://shopify/WebhookSubscription/$id", topic = topic, uri = uri)


class RenderOAuthInstallPageTest {

  private fun renderBase(
    shop: String = "acme.myshopify.com",
    monolithPersist: MonolithPersistOutcome = MonolithPersistOutcome.Persisted(storeId = StoreId(1L)),
    topics: List<WebhookTopicRegistration> = emptyList(),
    productCount: ProductCount? = ProductCount(count = 3, isExact = true),
  ): String =
    renderOAuthInstallPage(
      ShopInstallReport(
        shop = ShopDomain.parse(shop)!!,
        shopId = ShopifyShopId(9988L),
        monolithPersist = monolithPersist,
        productCount = productCount,
        webhookCallbackUrl = CALLBACK_URL,
        webhooks = WebhookRegistrationReport(topics),
      ),
    )

  @Test
  fun `renders a complete html document with App installed heading`() {
    val html = renderBase()
    assert(html.startsWith("<!DOCTYPE html>"))
    assert("<h1>App installed</h1>" in html)
    assert("Shop: acme.myshopify.com (id 9988)" in html)
  }

  /** Shopify stops counting at a cap (10,000 by default), so a count it did not finish is a lower bound. */
  @Test
  fun `the product count is shown as counted, and as a lower bound past Shopify's cap`() {
    assert("Products in the shop: 3" in renderBase())
    assert("Products in the shop: at least 10000" in renderBase(productCount = ProductCount(count = 10000, isExact = false)))
  }

  @Test
  fun `a failed product count reads as unknown rather than zero`() {
    assert("Products in the shop: unknown (lookup failed)" in renderBase(productCount = null))
  }

  @Test
  fun `escapes html-significant characters in failure error messages`() {
    val html = renderBase(
      topics = listOf(WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Failed("<img src=x onerror=alert(1)>"))),
    )
    assert("<img src=x onerror=alert(1)>" !in html)
    assert("&lt;img src=x onerror=alert(1)&gt;" in html)
  }

  @Test
  fun `monolith persisted renders the success notice with storeId`() {
    val html = renderBase(monolithPersist = MonolithPersistOutcome.Persisted(storeId = StoreId(42L)))
    assert("Shopify token saved via monolith" in html)
    assert("store_id=42" in html)
  }

  @Test
  fun `monolith failed renders status and detail`() {
    val html = renderBase(
      monolithPersist = MonolithPersistOutcome.Failed(httpStatus = 503, detail = "upstream timeout"),
    )
    assert("HTTP status 503" in html)
    assert("upstream timeout" in html)
  }

  @Test
  fun `monolith unreachable renders as no response`() {
    val html = renderBase(monolithPersist = MonolithPersistOutcome.Failed(httpStatus = null, detail = "connection refused"))
    assert("no response" in html)
    assert("connection refused" in html)
  }

  @Test
  fun `monolith failed truncates very long detail`() {
    val detail = "x".repeat(800)
    val html = renderBase(
      monolithPersist = MonolithPersistOutcome.Failed(httpStatus = 500, detail = detail),
    )
    assert("x".repeat(400) in html)
    assert("x".repeat(401) !in html)
  }

  @Test
  fun `the summary line counts active, added, failed and stale`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(subscription(1, "PRODUCTS_CREATE"))),
        WebhookTopicRegistration("PRODUCTS_UPDATE", WebhookTopicStatus.Added(subscription(2, "PRODUCTS_UPDATE"))),
        WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Failed("scope missing")),
        WebhookTopicRegistration(
          "ORDERS_UPDATED",
          WebhookTopicStatus.Added(subscription(3, "ORDERS_UPDATED")),
          stale = listOf(subscription(4, "ORDERS_UPDATED", uri = "https://old.example/webhooks/shopify")),
        ),
      ),
    )
    assert("1 already active, 2 added in this install, 1 failed, 1 pointing elsewhere." in html)
  }

  @Test
  fun `one table row per topic with its status, subscription id and stale uri`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(subscription(1, "PRODUCTS_CREATE"))),
        WebhookTopicRegistration(
          "ORDERS_CREATE",
          WebhookTopicStatus.Missing,
          stale = listOf(subscription(2, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify")),
        ),
      ),
    )
    assert("<td><code>PRODUCTS_CREATE</code></td>" in html)
    assert("gid://shopify/WebhookSubscription/1" in html)
    assert(">active<" in html)
    assert(">missing<" in html)
    assert("https://old.example/webhooks/shopify" in html)
    assert("gid://shopify/WebhookSubscription/2" in html)
  }

  @Test
  fun `no failure block when there are no failures`() {
    val html = renderBase(topics = listOf(WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(subscription(1, "PRODUCTS_CREATE")))))
    assert("Webhook registrations that failed" !in html)
  }

  @Test
  fun `failure block lists each failed topic`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Failed("permission denied")),
        WebhookTopicRegistration("ORDERS_UPDATED", WebhookTopicStatus.Failed("scope missing")),
      ),
    )
    assert("Webhook registrations that failed" in html)
    assert("PRODUCTS_CREATE" in html)
    assert("ORDERS_UPDATED" in html)
    assert("permission denied" in html)
    assert("scope missing" in html)
    assert("Reinstall the app after fixing." in html)
  }

  @Test
  fun `robots meta is set to noindex nofollow`() {
    val html = renderBase()
    assert("robots" in html)
    assert("noindex" in html)
    assert("nofollow" in html)
  }
}
