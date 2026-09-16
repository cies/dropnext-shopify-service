package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.ObsoleteWebhookSubscription
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAccessScopeReport
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import org.junit.jupiter.api.Test


private const val CALLBACK_URL = "https://dss.example.com/webhooks/shopify"
private val everyScope = ShopifyAccessScope.entries.map { it.handle }


class RenderOAuthInstallPageTest {

  private fun renderBase(
    shop: String = "acme.myshopify.com",
    monolithPersist: MonolithPersistOutcome = MonolithPersistOutcome.Persisted(storeId = StoreId(1L)),
    topics: List<WebhookTopicRegistration> = emptyList(),
    obsolete: List<ObsoleteWebhookSubscription> = emptyList(),
    productCount: ProductCount? = ProductCount(count = 3, isExact = true),
    accessScopes: ShopifyAccessScopeReport = ShopifyAccessScopeReport.from(everyScope),
  ): String =
    renderOAuthInstallPage(
      ShopInstallReport(
        shop = ShopDomain.parse(shop)!!,
        shopId = ShopifyShopId(9988L),
        accessScopes = accessScopes,
        monolithPersist = monolithPersist,
        productCount = productCount,
        webhookCallbackUrl = CALLBACK_URL,
        webhooks = WebhookRegistrationReport(topics, obsolete),
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
  fun `a complete grant says every required access scope is granted`() {
    val html = renderBase()
    assert("All ${ShopifyAccessScope.entries.size} required access scopes granted." in html)
    assert("did not grant every access scope" !in html)
  }

  /** A missing scope fails only the operations that need it, so the page names each scope with what it is for. */
  @Test
  fun `a grant without a required scope names it, what it is needed for and that it is missing`() {
    val html = renderBase(accessScopes = ShopifyAccessScopeReport.from(everyScope - "write_fulfillments"))
    assert("This shop did not grant every access scope the service needs" in html)
    assert("<td><code>write_fulfillments</code></td>" in html)
    assert(ShopifyAccessScope.WRITE_FULFILLMENTS.neededFor in html)
    assert(Regex("<strong>missing</strong>").findAll(html).count() == 1)
    assert(Regex(">granted<").findAll(html).count() == ShopifyAccessScope.entries.size - 1)
    assert("All ${ShopifyAccessScope.entries.size} required access scopes granted." !in html)
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
  fun `the summary line counts active, added, updated, repointed, failed and stale`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "PRODUCTS_CREATE"))),
        WebhookTopicRegistration("PRODUCTS_UPDATE", WebhookTopicStatus.Added(webhookSubscriptionStatus(2, "PRODUCTS_UPDATE"))),
        WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Failed("scope missing")),
        WebhookTopicRegistration(
          "ORDERS_UPDATED",
          WebhookTopicStatus.Added(webhookSubscriptionStatus(3, "ORDERS_UPDATED")),
          stale = listOf(webhookSubscriptionStatus(4, "ORDERS_UPDATED", uri = "https://old.example/webhooks/shopify")),
        ),
      ),
    )
    assert("1 already active, 2 added, 0 updated and 0 repointed from another URL in this install, 1 failed, 1 pointing elsewhere, 0 deleted for a topic the service no longer handles." in html)
  }

  @Test
  fun `a mismatched row names the payload fields Shopify sends and the ones the topic declares`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration(
          "ORDERS_CREATE",
          WebhookTopicStatus.Mismatched(webhookSubscriptionStatus(1, "ORDERS_CREATE"), expectedIncludeFields = listOf("id", "admin_graphql_api_id")),
        ),
      ),
    )
    assert(">mismatched<" in html)
    assert("gid://shopify/WebhookSubscription/1" in html)
    assert("<code>all fields</code>" in html)
    assert("<code>id, admin_graphql_api_id</code>" in html)
    assert(", filter: " !in html)
    assert(", format: " !in html)
  }

  @Test
  fun `a mismatched row names a filter and a format other than json`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration(
          "PRODUCTS_UPDATE",
          WebhookTopicStatus.Mismatched(
            webhookSubscriptionStatus(1, "PRODUCTS_UPDATE", filter = "vendor:Acme", format = "XML"),
            expectedIncludeFields = emptyList(),
          ),
        ),
      ),
    )
    assert(", filter: <code>vendor:Acme</code>" in html)
    assert(", format: <code>XML</code>" in html)
  }

  @Test
  fun `an updated and a repointed row show what this install changed`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Updated(webhookSubscriptionStatus(1, "ORDERS_CREATE"))),
        WebhookTopicRegistration(
          "PRODUCTS_UPDATE",
          WebhookTopicStatus.Repointed(webhookSubscriptionStatus(2, "PRODUCTS_UPDATE"), previousUri = "https://old.example/webhooks/shopify"),
        ),
      ),
    )
    assert(">updated<" in html)
    assert(">repointed<" in html)
    assert("gid://shopify/WebhookSubscription/2" in html)
    assert("https://old.example/webhooks/shopify" in html)
    assert("0 already active, 0 added, 1 updated and 1 repointed from another URL in this install, 0 failed, 0 pointing elsewhere, 0 deleted for a topic the service no longer handles." in html)
  }

  /** An update Shopify accepted without applying it is a failure the reader acts on, shown with what Shopify kept. */
  @Test
  fun `a not applied row compares the fields Shopify kept with the declared ones and is listed as a failure`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration(
          "PRODUCTS_CREATE",
          WebhookTopicStatus.NotApplied(webhookSubscriptionStatus(1, "PRODUCTS_CREATE", includeFields = listOf("id")), expectedIncludeFields = emptyList()),
        ),
      ),
    )
    assert(">not applied<" in html)
    assert("<code>id</code>" in html)
    assert("<code>all fields</code>" in html)
    assert("Webhook subscriptions that could not be registered or updated" in html)
    assert("Shopify accepted the update but did not apply it." in html)
    assert("0 repointed from another URL in this install, 1 failed" in html)
  }

  @Test
  fun `one table row per topic with its status, subscription id and stale uri`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "PRODUCTS_CREATE"))),
        WebhookTopicRegistration(
          "ORDERS_CREATE",
          WebhookTopicStatus.Missing,
          stale = listOf(webhookSubscriptionStatus(2, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify")),
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
    val html = renderBase(topics = listOf(WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "PRODUCTS_CREATE")))))
    assert("Webhook subscriptions that could not be registered or updated" !in html)
  }

  @Test
  fun `failure block lists each failed topic`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Failed("permission denied")),
        WebhookTopicRegistration("ORDERS_UPDATED", WebhookTopicStatus.Failed("scope missing")),
      ),
    )
    assert("Webhook subscriptions that could not be registered or updated:" in html)
    // The topic table above names every topic too; only the block after the heading proves the failures are listed.
    val failureBlock = html.substringAfter("Webhook subscriptions that could not be registered or updated:")
    assert("PRODUCTS_CREATE" in failureBlock)
    assert("ORDERS_UPDATED" in failureBlock)
    assert("permission denied" in failureBlock)
    assert("scope missing" in failureBlock)
    assert("Reinstall the app after fixing." in failureBlock)
    assert("the access scopes reported above" in failureBlock)
  }

  @Test
  fun `names what was subscribed for a topic no longer handled, deleted or not, with the deletion error escaped`() {
    val html = renderBase(
      obsolete = listOf(
        ObsoleteWebhookSubscription(webhookSubscriptionStatus(3, "ORDERS_UPDATED"), ObsoleteSubscriptionRemoval.Deleted),
        ObsoleteWebhookSubscription(webhookSubscriptionStatus(4, "CUSTOMERS_CREATE"), ObsoleteSubscriptionRemoval.Failed("<denied>")),
      ),
    )
    assert("1 deleted for a topic the service no longer handles." in html)
    assert("Subscriptions at the webhook callback URL for a topic the service no longer handles:" in html)
    assert("gid://shopify/WebhookSubscription/3" in html)
    assert("deletion failed: " in html)
    assert("&lt;denied&gt;" in html)
    assert("<denied>" !in html)
  }

  @Test
  fun `says nothing about topics no longer handled when nothing was subscribed for one`() {
    assert("for a topic the service no longer handles:" !in renderBase())
  }

  @Test
  fun `robots meta is set to noindex nofollow`() {
    assert("""<meta name="robots" content="noindex, nofollow">""" in renderBase())
  }
}
