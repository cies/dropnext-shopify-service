package dropnext.dss.lib.shopify.graphql

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.lib.shopify.token.ShopLookup
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.dss.testutil.helper.withFakeShopifyServer
import dropnext.dss.workflow.resolveShopTokenFromMonolith
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


/** Deliberately not the default: the factory must use the version it was configured with. */
private const val API_VERSION = "2025-01"


/**
 * What the factory adds to the token store: a service bound to the shop, the configured API version and the token, and
 * the eviction of a token Shopify refuses. Whether a shop resolves at all (`Missing`, `Unavailable`, the monolith asked
 * once and by subdomain) is the store's and its fallback's, pinned in `InMemoryShopTokenStoreTest` and
 * `ResolveShopTokenFromMonolithTest`; the factory passes that answer on unchanged.
 *
 * Requests go through the rewriting client to a fake Shopify, so the URL the factory builds is
 * observable rather than assumed.
 */
class HttpShopifyGraphqlServiceFactoryTest {

  /** A fake Shopify per test rather than per class: nothing then has to remember to clear it between them. */
  private fun withFactory(block: suspend (FakeShopifyGraphqlServer, HttpClient) -> Unit) =
    withFakeShopifyServer { shopify, httpClient -> runBlocking { block(shopify, httpClient) } }

  /** Sends no request, so no fake Shopify is started for it. */
  @Test
  fun `a cached token is used without asking the monolith`() = testHttpClient().use { httpClient ->
    runBlocking {
      val monolith = FakeMonolithService()
      val factory = factoryFor(httpClient, monolith, cached = mapOf(ACME_SHOP to ShopifyAdminToken("shpat_cached")))

      val lookup = factory.forShop(ACME_SHOP)

      assert((lookup as ShopLookup.Found).value.shop == ACME_SHOP)
      assert(monolith.getStoreCalls.isEmpty())
    }
  }

  @Test
  fun `the service it hands back talks to the configured API version with the shop's token`() = withFactory { shopify, httpClient ->
    val factory = factoryFor(httpClient, FakeMonolithService(), cached = mapOf(ACME_SHOP to ShopifyAdminToken("shpat_cached")))
    val service = (factory.forShop(ACME_SHOP) as ShopLookup.Found).value

    service.shopIdentity()

    val call = shopify.calls.single()
    assert(call.authorization == "shpat_cached")
    assert(call.path == "/admin/api/$API_VERSION/graphql.json")
  }

  /**
   * A reinstall through another instance leaves this one holding a token Shopify now refuses. The
   * `401` must evict it, so the next request re-resolves from the monolith instead of failing until a restart.
   */
  @Test
  fun `a 401 from Shopify evicts the token and the next call asks the monolith again`() = withFactory { shopify, httpClient ->
    val monolith = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_after_reinstall") }
    val factory = factoryFor(httpClient, monolith, cached = mapOf(ACME_SHOP to ShopifyAdminToken("shpat_revoked")))
    shopify.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)

    val rejected = (factory.forShop(ACME_SHOP) as ShopLookup.Found).value.shopIdentity()
    assert(rejected.failureReason() == ShopifyError.TokenRejected(401))

    factory.forShop(ACME_SHOP)
    assert(monolith.getStoreCalls.single() == "acme")
  }

  /** The `401` of a request that started out with the old token must not throw away the token a reinstall remembered meanwhile. */
  @Test
  fun `a 401 for an old token leaves a newer token in place`() = withFactory { shopify, httpClient ->
    val tokens = InMemoryShopTokenStore(mapOf(ACME_SHOP to ShopifyAdminToken("shpat_revoked")))
    val factory = HttpShopifyGraphqlServiceFactory(httpClient, tokens, API_VERSION)
    val serviceWithOldToken = (factory.forShop(ACME_SHOP) as ShopLookup.Found).value
    tokens.remember(ACME_SHOP, ShopifyAdminToken("shpat_after_reinstall"))
    shopify.stubRaw("ShopIdentity", """{"errors":"[API] Invalid API key or access token"}""", HttpStatusCode.Unauthorized)

    serviceWithOldToken.shopIdentity()

    assert(tokens.cached(ACME_SHOP) == ShopifyAdminToken("shpat_after_reinstall"))
  }

  // ---------- helpers ----------

  private fun factoryFor(
    httpClient: HttpClient,
    monolith: FakeMonolithService,
    cached: Map<ShopDomain, ShopifyAdminToken>,
  ): HttpShopifyGraphqlServiceFactory {
    val tokens = InMemoryShopTokenStore(cached) { shop -> resolveShopTokenFromMonolith(monolith, shop) }
    return HttpShopifyGraphqlServiceFactory(httpClient, tokens, API_VERSION)
  }
}
