package dropnext.dss

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.handler.WEBHOOK_MONOLITH_MAX_RETRIES
import dropnext.dss.lib.ktor.MONOLITH_MAX_RETRIES
import dropnext.dss.lib.ktor.createMonolithHttpClient
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.shopifyRewritingHttpClient
import dropnext.dss.testutil.helper.withDssApp
import dropnext.graphql.generated.GetOrderForDss
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private const val APP_SECRET = "shpss_app_secret"


/**
 * The defaults of `dssDependencies`, which every other test overrides. What is pinned is the
 * production token store: empty at start, and asking the monolith once on a miss, which is how a
 * restarted instance gets its tokens back.
 */
class DependenciesTest {

  @Test
  fun `a shop is looked up on the monolith once and then remembered`() =
    withFakeShopify { rewritingClient ->
      val monolith = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_from_monolith") }
      val deps = dssDependencies(config = testConfig(), httpClient = rewritingClient, monolithService = monolith)
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        assert(client.get("${Paths.apiCheck}?shop=acme.myshopify.com").status == HttpStatusCode.OK)
        assert(client.get("${Paths.apiCheck}?shop=acme.myshopify.com").status == HttpStatusCode.OK)
        assert(monolith.getStoreCalls == listOf("acme"))
      }
    }

  @Test
  fun `a shop the monolith does not know has no token`() =
    withFakeShopify { rewritingClient ->
      val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
      val deps = dssDependencies(config = testConfig(), httpClient = rewritingClient, monolithService = monolith)
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        val r = client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
        assert(r.status == HttpStatusCode.Unauthorized)
        assert(monolith.getStoreCalls == listOf("acme"))
      }
    }

  /** An unreachable monolith answers nothing worth caching: the check says to retry, and the retry asks the monolith again. */
  @Test
  fun `a shop whose token lookup cannot reach the monolith is a 502 and is looked up again next time`() =
    withFakeShopify { rewritingClient ->
      val monolith = FakeMonolithService().apply { getStoreTransportFailure = true }
      val deps = dssDependencies(config = testConfig(), httpClient = rewritingClient, monolithService = monolith)
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        assert(client.get("${Paths.apiCheck}?shop=acme.myshopify.com").status == HttpStatusCode.BadGateway)
        assert(client.get("${Paths.apiCheck}?shop=acme.myshopify.com").status == HttpStatusCode.BadGateway)
        assert(monolith.getStoreCalls == listOf("acme", "acme"))
      }
    }

  /**
   * The readiness check scans the shop's webhook subscriptions through the production factory, so
   * the graph under test points at a fake Shopify that answers the scan with nothing subscribed.
   */
  private fun withFakeShopify(block: (HttpClient) -> Unit) {
    val shopifyServer = FakeShopifyGraphqlServer()
    val rewritingClient = shopifyRewritingHttpClient(shopifyServer.start())
    try {
      shopifyServer.stubData(
        "GetWebhookSubscriptions",
        GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = emptyList())),
        GetWebhookSubscriptions.Result.serializer(),
      )
      block(rewritingClient)
    } finally {
      // The application closes the client it was given when it stops; the server is ours to stop.
      shopifyServer.stop()
    }
  }

  /** A webhook has four seconds and a monolith-facing route thirty, so the monolith calls they make repeat differently. */
  @Test
  fun `a webhook repeats a failed monolith call once, a monolith-facing route three times`() {
    val monolithServer = FakeMonolithHttpServer().apply {
      defaultResponse = FakeMonolithHttpServer.CannedResponse(HttpStatusCode.ServiceUnavailable, """{"error":"down"}""")
    }
    val monolithPort = monolithServer.start()
    val shopifyServer = FakeShopifyGraphqlServer()
    val rewritingClient = shopifyRewritingHttpClient(shopifyServer.start())
    try {
      shopifyServer.stubData("GetOrderForDss", GetOrderForDss.Result(order = minimalOrder()), GetOrderForDss.Result.serializer())
      val deps = dssDependencies(
        config = testConfig(appClientSecret = APP_SECRET, monolithBaseUrl = "http://localhost:$monolithPort"),
        httpClient = rewritingClient,
        // A token in hand: the monolith that is down would otherwise stop the webhook at its token lookup.
        shopTokens = InMemoryShopTokenStore(mapOf(acmeShop to ShopifyAdminToken("shpat_test"))),
        // The production retry counts over a backoff a test need not wait out.
        monolithHttpClient = createMonolithHttpClient(rewritingClient, retryBaseDelayMillis = 5),
        webhookMonolithHttpClient = createMonolithHttpClient(rewritingClient, retryBaseDelayMillis = 5, maxRetries = WEBHOOK_MONOLITH_MAX_RETRIES),
      )
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}"""
        val webhook = client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", acmeShop.normalizedShopifyHost)
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(APP_SECRET, body.toByteArray()))
          setBody(body)
        }
        assert(webhook.status == HttpStatusCode.BadGateway)
        assert(monolithServer.requests.count { it.path == "/orders" } == 1 + WEBHOOK_MONOLITH_MAX_RETRIES)

        val put = client.put(Paths.storesApiKey) {
          contentType(ContentType.Application.Json)
          setBody("""{"shopify_subdomain":"acme","shopify_shop_id":1,"api_key":"shpat_x"}""")
        }
        assert(put.status == HttpStatusCode.BadGateway)
        assert(monolithServer.requests.count { it.path == "/stores/api-key" } == 1 + MONOLITH_MAX_RETRIES)
      }
    } finally {
      shopifyServer.stop()
      monolithServer.stop()
    }
  }

  /**
   * The token the monolith hands out has to reach Shopify itself, through the production store and
   * factory: the readiness check alone would pass with a store that never hands the token out.
   */
  @Test
  fun `the token the monolith hands out is what a webhook presents to Shopify`() {
    val shopifyServer = FakeShopifyGraphqlServer()
    val rewritingClient = shopifyRewritingHttpClient(shopifyServer.start())
    try {
      shopifyServer.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
      val deps = dssDependencies(
        config = testConfig(appClientSecret = APP_SECRET),
        httpClient = rewritingClient,
        monolithService = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_from_monolith") },
      )
      withDssApp(deps) { client ->
        val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}"""
        val r = client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", acmeShop.normalizedShopifyHost)
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(APP_SECRET, body.toByteArray()))
          setBody(body)
        }
        assert(r.status == HttpStatusCode.OK)
      }
      val call = shopifyServer.calls.single()
      assert(call.operationName == "GetOrderForDss")
      assert(call.authorization == "shpat_from_monolith")
    } finally {
      // The application closes the client it was given when it stops; the server is ours to stop.
      shopifyServer.stop()
    }
  }
}
