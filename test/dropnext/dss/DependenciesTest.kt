package dropnext.dss

import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.handler.WEBHOOK_MONOLITH_MAX_RETRIES
import dropnext.dss.lib.ktor.MONOLITH_MAX_RETRIES
import dropnext.dss.lib.ktor.createMonolithHttpClient
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.TEST_APP_SECRET
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.withDssApp
import dropnext.dss.testutil.helper.withFakeMonolithServer
import dropnext.dss.testutil.helper.withFakeShopifyServer
import dropnext.graphql.generated.GetOrderForDss
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test


/**
 * The defaults of `dssDependencies`, which every other test overrides: which monolith client each route family gets,
 * and that the production store and factory hand the monolith's token on to Shopify. The store's own behavior on a
 * miss (one lookup, nothing cached from a failed one) is `InMemoryShopTokenStoreTest`'s and
 * `ResolveShopTokenFromMonolithTest`'s.
 */
class DependenciesTest {

  /** A webhook has four seconds and a monolith-facing route thirty, so the monolith calls they make repeat differently. */
  @Test
  fun `a webhook repeats a failed monolith call once, a monolith-facing route three times`() {
    withFakeMonolithServer { monolithServer, monolithBaseUrl ->
      monolithServer.defaultResponse =
        FakeMonolithHttpServer.CannedResponse(HttpStatusCode.ServiceUnavailable, """{"error":"down"}""")
      withFakeShopifyServer { shopifyServer, rewritingClient ->
        shopifyServer.stubData("GetOrderForDss", GetOrderForDss.Result(order = minimalOrder()), GetOrderForDss.Result.serializer())
        val deps = dssDependencies(
          config = testConfig(appClientSecret = TEST_APP_SECRET, monolithBaseUrl = monolithBaseUrl),
          httpClient = rewritingClient,
          // A token in hand: the monolith that is down would otherwise stop the webhook at its token lookup.
          shopTokens = InMemoryShopTokenStore(mapOf(ACME_SHOP to ShopifyAdminToken("shpat_test"))),
          // The production retry counts over a backoff a test need not wait out.
          monolithHttpClient = createMonolithHttpClient(rewritingClient, retryBaseDelayMillis = 5),
          webhookMonolithHttpClient = createMonolithHttpClient(rewritingClient, retryBaseDelayMillis = 5, maxRetries = WEBHOOK_MONOLITH_MAX_RETRIES),
        )
        withDssApp(deps, authenticateAsMonolith = true) { client ->
          val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}"""
          val webhook = client.post(Paths.webhooksShopify) {
            header("X-Shopify-Topic", "orders/create")
            header("X-Shopify-Shop-Domain", ACME_SHOP.normalizedShopifyHost)
            header("X-Shopify-Hmac-Sha256", base64HmacSha256(TEST_APP_SECRET, body.toByteArray()))
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
      }
    }
  }

  /**
   * The token the monolith hands out has to reach Shopify itself, through the production store and
   * factory: the readiness check alone would pass with a store that never hands the token out.
   */
  @Test
  fun `the token the monolith hands out is what a webhook presents to Shopify`() {
    withFakeShopifyServer { shopifyServer, rewritingClient ->
      shopifyServer.stubData("GetOrderForDss", GetOrderForDss.Result(order = null), GetOrderForDss.Result.serializer())
      val deps = dssDependencies(
        config = testConfig(appClientSecret = TEST_APP_SECRET),
        httpClient = rewritingClient,
        monolithService = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_from_monolith") },
      )
      withDssApp(deps) { client ->
        val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}"""
        val r = client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", ACME_SHOP.normalizedShopifyHost)
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(TEST_APP_SECRET, body.toByteArray()))
          setBody(body)
        }
        assert(r.status == HttpStatusCode.OK)
      }
      val call = shopifyServer.calls.single()
      assert(call.operationName == "GetOrderForDss")
      assert(call.authorization == "shpat_from_monolith")
    }
  }
}
