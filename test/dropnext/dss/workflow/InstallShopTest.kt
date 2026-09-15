package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyShopId
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.shopify.graphql.ShopIdentityInfo
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
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
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Success(ShopIdentityInfo(shopId = ShopifyShopId(9988L), domain = acmeShop))
      productCountResult = Success(ProductCount(count = 3, isExact = true))
    }
    val report = installShop(shopify, FakeMonolithService(), tokens, installedToken, everyScope, CALLBACK_URL)

    assert(report.shop == acmeShop)
    assert(report.shopId == ShopifyShopId(9988L))
    assert(report.productCount == ProductCount(count = 3, isExact = true))
    assert(report.monolithPersist is MonolithPersistOutcome.Persisted)
    assert(tokens.cached(acmeShop) == installedToken)
  }

  /** Without an identity the workflow falls back to the shop the service is bound to. */
  @Test
  fun `a failing identity lookup still caches the token under the bound shop`() = runBlocking {
    val tokens = InMemoryShopTokenStore()
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Failure(ShopifyError.Network("shop identity unreachable"))
    }
    val report = installShop(shopify, FakeMonolithService(), tokens, installedToken, everyScope, CALLBACK_URL)

    assert(report.shopId == null)
    assert(report.shop == acmeShop)
    assert(tokens.cached(acmeShop) == installedToken)
  }

  /** A shop with no id still reaches the monolith, which is told `0` rather than nothing. */
  @Test
  fun `a failing identity lookup forwards a null shop id to the monolith`() = runBlocking {
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      shopIdentityResult = Failure(ShopifyError.Network("shop identity unreachable"))
    }
    installShop(shopify, monolith, InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    assert(monolith.putStoreApiKeyCalls.single().shopifyShopId == null)
  }

  @Test
  fun `a rejected monolith persist is reported with its status`() = runBlocking {
    val monolith = FakeMonolithService().apply { putStoreApiKeyStatus = 500 }
    val tokens = InMemoryShopTokenStore()
    val report = installShop(FakeShopifyGraphqlService(acmeShop), monolith, tokens, installedToken, everyScope, CALLBACK_URL)

    val persist = report.monolithPersist
    assert(persist is MonolithPersistOutcome.Failed)
    assert((persist as MonolithPersistOutcome.Failed).httpStatus == 500)
    // The install continues: the token is ours whether or not the monolith took it.
    assert(tokens.cached(acmeShop) == installedToken)
  }

  @Test
  fun `a failing product count leaves the count unknown rather than zero`() = runBlocking {
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
      productCountResult = Failure(ShopifyError.GraphqlError("Throttled"))
    }
    val report = installShop(shopify, FakeMonolithService(), InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    // Null and 0 mean different things on the page: "could not ask" versus "the catalogue is empty".
    assert(report.productCount == null)
  }

  @Test
  fun `a webhook topic that cannot be registered is named in the report`() = runBlocking {
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
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
    val canonical = ShopDomain.parse("acme-canonical.myshopify.com")!!
    val tokens = InMemoryShopTokenStore()
    val monolith = FakeMonolithService()
    val shopify = FakeShopifyGraphqlService(acmeShop).apply {
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
      FakeShopifyGraphqlService(acmeShop),
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
    val shopify = FakeShopifyGraphqlService(acmeShop)
    val report = installShop(shopify, monolith, tokens, installedToken, everyScope - "write_fulfillments", CALLBACK_URL)

    assert(report.accessScopes.missing == listOf(ShopifyAccessScope.WRITE_FULFILLMENTS))
    assert(tokens.cached(acmeShop) == installedToken)
    assert(monolith.putStoreApiKeyCalls.size == 1)
    assert(shopify.registerWebhookCalls.size == ShopifyWebhookTopic.known.size)
  }

  @Test
  fun `a grant of every required scope reports nothing missing`() = runBlocking {
    val report = installShop(FakeShopifyGraphqlService(acmeShop), FakeMonolithService(), InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL)

    assert(report.accessScopes.isComplete)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a grant without required scopes logs one warn line naming them`() {
    val granted = everyScope - "write_fulfillments" - "write_third_party_fulfillment_orders"
    val lines = capturingLogs {
      runBlocking { installShop(FakeShopifyGraphqlService(acmeShop), FakeMonolithService(), InMemoryShopTokenStore(), installedToken, granted, CALLBACK_URL) }
    }

    val scopeLines = lines.filter { "OAuth grant lacks required scopes" in it }
    assert(scopeLines.size == 1)
    assert(scopeLines.single().startsWith("WARN OAuth grant lacks required scopes missing=write_third_party_fulfillment_orders,write_fulfillments {"))
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a complete grant logs no scope line`() {
    val lines = capturingLogs {
      runBlocking { installShop(FakeShopifyGraphqlService(acmeShop), FakeMonolithService(), InMemoryShopTokenStore(), installedToken, everyScope, CALLBACK_URL) }
    }

    assert(lines.none { "OAuth grant lacks required scopes" in it })
  }
}
