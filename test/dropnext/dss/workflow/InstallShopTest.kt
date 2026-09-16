package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.StoreId
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.shopify.graphql.ShopIdentityInfo
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.CANONICAL_ACME_SHOP
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val installedToken = ShopifyAdminToken("shpat_installed")
private const val CALLBACK_URL = "https://dss.test/webhooks/shopify"
private val everyScope = ShopifyAccessScope.entries.map { it.handle }


/**
 * The post-OAuth install, which is defined by what it does when a step fails: the shop has already
 * granted the token, so every failure has to end up on the confirmation page rather than aborting
 * the install and inviting the merchant to try again.
 *
 * The rendering of these reports is covered by `RenderOAuthInstallPageTest`; what is checked here is
 * that the workflow actually produces them.
 */
class InstallShopTest {

  @Test
  fun `a clean install reports the shop id, the product count and the cached token`() = runBlocking {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService().apply { putStoreApiKeyStoreId = 4242L }
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = ACME_SHOP))
      productCountResult = Success(ProductCount(count = 3, isExact = true))
    }
    val report = installShop(shopify, monolith, tokens, installedToken, everyScope, CALLBACK_URL)

    assert(report.shop == ACME_SHOP)
    assert(report.shopId == ShopifyShopId(9988L))
    assert(report.productCount == ProductCount(count = 3, isExact = true))
    assert(report.monolithPersist == MonolithPersistOutcome.Persisted(StoreId(4242L)))
    assert(tokens.cached(ACME_SHOP) == installedToken)
  }

  /** The monolith is where the token outlives a restart, so what it is sent is the install's other half. */
  @Test
  fun `a clean install persists the granted token and the shop id under the shop's subdomain`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = ACME_SHOP))
    }
    installShop(shopify, monolith, InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    val persisted = monolith.putStoreApiKeyCalls.single()
    assert(persisted.shopifySubdomain == "acme")
    assert(persisted.apiKey == installedToken.value)
    assert(persisted.shopifyShopId == 9988L)
  }

  /** Without an identity the workflow falls back to the shop the service is bound to. */
  @Test
  fun `a failing identity lookup still caches the token under the bound shop`() = runBlocking {
    val tokens = InMemoryShopTokenStore()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Failure(ShopifyError.Network("shop identity unreachable"))
    }
    val report = installShop(shopify, FakeMonolithService(), tokens, installedToken, everyScope, CALLBACK_URL)

    assert(report.shopId == null)
    assert(report.shop == ACME_SHOP)
    assert(tokens.cached(ACME_SHOP) == installedToken)
  }

  /** A shop with no id still reaches the monolith, which keeps the id it has rather than being told a made-up `0`. */
  @Test
  fun `a failing identity lookup forwards a null shop id to the monolith`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Failure(ShopifyError.Network("shop identity unreachable"))
    }
    installShop(shopify, monolith, InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    assert(monolith.putStoreApiKeyCalls.single().shopifyShopId == null)
  }

  @Test
  fun `a rejected monolith persist is reported with its status`() = runBlocking {
    val monolith = FakeMonolithService().apply { putStoreApiKeyStatus = 500 }
    val tokens = InMemoryShopTokenStore()
    val report = installShop(FakeShopifyGraphqlService(ACME_SHOP), monolith, tokens, installedToken, everyScope, CALLBACK_URL)

    assert(report.monolithPersist == MonolithPersistOutcome.Failed(httpStatus = 500, detail = "forced fail"))
    // The install continues: the token is ours whether or not the monolith took it.
    assert(tokens.cached(ACME_SHOP) == installedToken)
  }

  /** No status is how the page tells "the monolith did not answer" from "the monolith said no". */
  @Test
  fun `a monolith that does not answer the persist is reported without a status`() = runBlocking {
    val monolith = FakeMonolithService().apply { putStoreApiKeyTransportFailure = true }
    val tokens = InMemoryShopTokenStore()
    val report = installShop(FakeShopifyGraphqlService(ACME_SHOP), monolith, tokens, installedToken, everyScope, CALLBACK_URL)

    assert(report.monolithPersist == MonolithPersistOutcome.Failed(httpStatus = null, detail = "forced transport failure"))
    assert(monolith.putStoreApiKeyCalls.size == 1)
    assert(tokens.cached(ACME_SHOP) == installedToken)
  }

  @Test
  fun `a failing product count leaves the count unknown rather than zero`() = runBlocking {
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      productCountResult = Failure(ShopifyError.GraphqlError("Throttled"))
    }
    val report = installShop(shopify, FakeMonolithService(), InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    // Null and 0 mean different things on the page: "could not ask" versus "the catalogue is empty".
    assert(report.productCount == null)
  }

  @Test
  fun `a webhook topic that cannot be registered is named in the report`() = runBlocking {
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      registerWebhookResult = Failure(ShopifyError.UserError(listOf("address is not allowed")))
    }
    val report = installShop(shopify, FakeMonolithService(), InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    assert(report.webhooks.addedCount == 0)
    assert(report.webhooks.failures.size == report.webhooks.topics.size)
    assert(report.webhooks.failures.all { "address is not allowed" in (it.status as WebhookTopicStatus.Failed).error })
  }

  /**
   * Shopify may redirect for one host while the shop's canonical `myshopify.com` host is another,
   * and a webhook arrives under the latter: that is the domain the token has to be found under.
   */
  @Test
  fun `the token is remembered under the canonical domain Shopify reports, not only the callback shop`() = runBlocking {
    val canonical = CANONICAL_ACME_SHOP
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = canonical))
    }

    val report = installShop(shopify, monolith, tokens, installedToken, everyScope, CALLBACK_URL)

    assert(report.shop == canonical)
    assert(tokens.cached(canonical) == installedToken)
    assert(monolith.putStoreApiKeyCalls.single().shopifySubdomain == "acme-canonical")
  }

  @Test
  fun `the callback url the webhooks were registered for is carried into the report`() = runBlocking {

    val report = installShop(
      FakeShopifyGraphqlService(ACME_SHOP),
      FakeMonolithService(),
      InMemoryShopTokenStore(),
      installedToken,
      everyScope,
      CALLBACK_URL,
    )
    assert(report.webhookCallbackUrl == CALLBACK_URL)
  }

  /** The merchant approved less than was asked for: the token still does what its scopes cover, so nothing is skipped. */
  @Test
  fun `a grant without a required scope is reported, and every install step runs anyway`() = runBlocking {
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(ACME_SHOP)
    val report = installShop(shopify, monolith, tokens, installedToken, everyScope - "write_fulfillments", CALLBACK_URL)

    assert(report.accessScopes.missing == listOf(ShopifyAccessScope.WRITE_FULFILLMENTS))
    assert(tokens.cached(ACME_SHOP) == installedToken)
    assert(monolith.putStoreApiKeyCalls.size == 1)
    assert(shopify.registerWebhookCalls.size == ShopifyWebhookTopic.known.size)
  }

  @Test
  fun `a grant of every required scope reports nothing missing`() = runBlocking {
    val report = installShop(FakeShopifyGraphqlService(ACME_SHOP), FakeMonolithService(), InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    assert(report.accessScopes.isComplete)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a grant without required scopes logs one warn line naming them`() {
    val granted = everyScope - "write_fulfillments" - "write_third_party_fulfillment_orders"
    val lines = capturingLogs {
      runBlocking { installShop(FakeShopifyGraphqlService(ACME_SHOP), FakeMonolithService(), InMemoryShopTokenStore(), installedToken, granted, CALLBACK_URL) }
    }

    val scopeLines = lines.filter { "OAuth grant lacks required scopes" in it }
    assert(scopeLines.size == 1)
    assert(scopeLines.single().startsWith("WARN OAuth grant lacks required scopes missing=write_third_party_fulfillment_orders,write_fulfillments {"))
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a complete grant logs no scope line`() {
    val lines = capturingLogs {
      runBlocking { installShop(FakeShopifyGraphqlService(ACME_SHOP), FakeMonolithService(), InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL) }
    }

    assert(lines.none { "OAuth grant lacks required scopes" in it })
  }
}
