package dropnext.dss

import dropnext.dss.boot.warmup.WarmUp
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.TEST_APP_SECRET
import dropnext.dss.testutil.fixture.TEST_MONOLITH_TO_DSS_API_KEY
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.awaitUntil
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val ADMIN_TOKEN = ShopifyAdminToken("shpat_super_secret_admin_token")


/**
 * What the composition root guarantees for every route, rather than what any one handler does.
 */
class DssModuleTest {

  @Test
  fun `every response carries the trace id header`() = withDssApp(testDependencies(shopify = FakeShopifyGraphqlService())) { client ->
    val r = client.get(Paths.health)
    assert(r.headers["X-Trace-Id"] != null)
  }

  @Test
  fun `a caller-supplied request id becomes the trace id`() = withDssApp(testDependencies(shopify = FakeShopifyGraphqlService())) { client ->
    val r = client.get(Paths.health) { header("X-Request-Id", "trace-from-the-monolith") }
    assert(r.headers["X-Trace-Id"] == "trace-from-the-monolith")
  }

  /** Ktor's own shutdown raises `ApplicationStopped`; the graph's HTTP clients must not outlive it. */
  @Test
  fun `stopping the application closes the dependency graph`() {
    val httpClient = testHttpClient()
    val deps = dssDependencies(
      config = testConfig(),
      httpClient = httpClient,
      monolithService = FakeMonolithService(),
    )
    withDssApp(deps) { client -> client.get(Paths.health) }
    assert(!httpClient.isActive)
  }

  /** "Before the handler runs" is the point: the guard must answer without the order ever being read from Shopify. */
  @Test
  fun `an unauthenticated monolith route is refused before the handler runs`() {
    val shopify = FakeShopifyGraphqlService()
    withDssApp(testDependencies(shopify = shopify)) { client ->
      val r = client.post(Paths.syncShipmentsWithFulfillments) {
        contentType(ContentType.Application.Json)
        setBody("""{"shopify_subdomain":"acme","shopify_order_id":1,"shipments":[]}""")
      }
      assert(r.status == HttpStatusCode.Unauthorized)
      assert(r.headers["WWW-Authenticate"] == "Bearer realm=dss-internal")
      assert(shopify.orderForDssCalls.isEmpty())
    }
  }

  /**
   * The rule `CLAUDE.md` states in prose — no Admin tokens, no `Authorization` headers, no bodies in
   * the logs — checked at runtime rather than by reading the code. The paths exercised are the ones
   * that actually hold a secret: a signed webhook, a token write, and a diagnostics read.
   */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `no secret reaches the logs`() {
    val body = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001","domain":"acme.myshopify.com"}"""
    val lines = capturingLogs {
      withDssApp(testDependencies(shopify = FakeShopifyGraphqlService(), shopTokens = InMemoryShopTokenStore(mapOf(ACME_SHOP to ADMIN_TOKEN)))) { client ->
        client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", ACME_SHOP.normalizedShopifyHost)
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(TEST_APP_SECRET, body.toByteArray(StandardCharsets.UTF_8)))
          setBody(body)
        }
        client.put(Paths.storesApiKey) {
          header("Authorization", "Bearer $TEST_MONOLITH_TO_DSS_API_KEY")
          contentType(ContentType.Application.Json)
          setBody("""{"shopify_subdomain":"acme","api_key":"${ADMIN_TOKEN.value}","shopify_shop_id":99}""")
        }
        client.get("${Paths.apiCheck}?shop=acme.myshopify.com")
      }
    }

    val logged = lines.joinToString("\n")
    // Something was logged, or this test would pass by logging nothing at all.
    assert(lines.isNotEmpty())
    assert(ADMIN_TOKEN.value !in logged)
    assert("shpat_" !in logged)
    assert(TEST_MONOLITH_TO_DSS_API_KEY !in logged)
    assert(TEST_APP_SECRET !in logged)
    assert("Bearer " !in logged)
  }

  /**
   * The whole chain, through the production client and service: the id the monolith sends in is
   * the id on the call we make back to it, so its log line for that call is findable from ours.
   */
  @Test
  fun `the trace id of an inbound monolith request is forwarded on the outbound monolith call`() {
    val monolithServer = FakeMonolithHttpServer()
    val port = monolithServer.start()
    try {
      monolithServer.enqueue(HttpStatusCode.OK, """{"store_id":7}""")
      val deps = dssDependencies(
        config = testConfig(monolithToDssApiKey = TEST_MONOLITH_TO_DSS_API_KEY, monolithBaseUrl = "http://localhost:$port"),
        httpClient = testHttpClient(),
      )
      withDssApp(deps, authenticateAsMonolith = true) { client ->
        val r = client.put(Paths.storesApiKey) {
          header("X-Trace-Id", "trace-from-the-monolith")
          contentType(ContentType.Application.Json)
          setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", shopifyShopId = 99L, apiKey = "shpat_x"))
        }
        assert(r.status == HttpStatusCode.OK)
        assert(r.body<UpdateStoreApiKeyResponse>().storeId == 7L)
      }
      val forwarded = monolithServer.requests.single()
      assert(forwarded.path == "/stores/api-key")
      assert(forwarded.headers["X-Trace-Id"] == listOf("trace-from-the-monolith"))
    } finally {
      monolithServer.stop()
    }
  }

  /** A Shopify webhook carries no trace id; the one minted for it is what the monolith has to see. */
  @Test
  fun `a minted trace id is forwarded on the outbound monolith call too`() {
    val monolithServer = FakeMonolithHttpServer()
    val port = monolithServer.start()
    try {
      monolithServer.enqueue(HttpStatusCode.OK, """{"store_id":7}""")
      val deps = dssDependencies(
        config = testConfig(monolithToDssApiKey = TEST_MONOLITH_TO_DSS_API_KEY, monolithBaseUrl = "http://localhost:$port"),
        httpClient = testHttpClient(),
      )
      val minted = withDssApp(deps, authenticateAsMonolith = true) { client ->
        client.put(Paths.storesApiKey) {
          contentType(ContentType.Application.Json)
          setBody(UpdateStoreApiKeyRequest(shopifySubdomain = "acme", shopifyShopId = 99L, apiKey = "shpat_x"))
        }.headers["X-Trace-Id"]
      }
      assert(minted != null)
      assert(monolithServer.requests.single().headers["X-Trace-Id"] == listOf(minted))
    } finally {
      monolithServer.stop()
    }
  }

  /**
   * The readiness gate the load balancer relies on: `/health` is a `503` while the warm-up runs, so a task that is
   * still cold stays out of rotation, and a `200` from the moment it ends. The warming answer names the running version
   * as the ready one does, so one probe reads either.
   */
  @Test
  fun `health answers 503 while the warm-up runs and 200 once it is done`() {
    val gate = CompletableDeferred<Unit>()
    val deps = testDependencies(shopify = FakeShopifyGraphqlService())
    withDssApp(deps, warmUp = WarmUp(5.seconds) { gate.await() }) { client ->
      val warming = client.get(Paths.health)
      assert(warming.status == HttpStatusCode.ServiceUnavailable)
      val warmingBody = warming.body<JsonObject>()
      assert(warmingBody["status"]!!.jsonPrimitive.content == "warming_up")
      assert(warmingBody["version"]!!.jsonPrimitive.content == "test-version")
      gate.complete(Unit)
      assert(awaitUntil { deps.readiness.isReady })
      val ready = client.get(Paths.health)
      assert(ready.status == HttpStatusCode.OK)
      assert(ready.body<JsonObject>()["status"]!!.jsonPrimitive.content == "ok")
    }
  }

  /** Only `/health` is gated: a request that reaches a warming task is served. */
  @Test
  fun `every other route is served while the warm-up runs`() {
    val gate = CompletableDeferred<Unit>()
    val deps = testDependencies(shopify = FakeShopifyGraphqlService())
    withDssApp(deps, warmUp = WarmUp(5.seconds) { gate.await() }) { client ->
      assert(client.get(Paths.api).status == HttpStatusCode.OK)
      // The gate is still shut, so the `200` above was answered by a task that is not ready yet.
      assert(!deps.readiness.isReady)
      gate.complete(Unit)
    }
  }
}
