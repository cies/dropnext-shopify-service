package dropnext.dss

import dropnext.dss.boot.warmup.WarmUp
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.contract.UpdateStoreApiKeyResponse
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.fake.FakeMonolithService

import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private const val APP_SECRET = "shpss_app_secret"
private val MONOLITH_TO_DSS_API_KEY = "k".repeat(32)
private val ADMIN_TOKEN = ShopifyAdminToken("shpat_super_secret_admin_token")


/**
 * What the composition root guarantees for every route, rather than what any one handler does.
 */
class DssModuleTest {

  @Test
  fun `every response carries the trace id header`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.health)
    assert(r.headers["X-Trace-Id"] != null)
  }

  @Test
  fun `a caller-supplied request id becomes the trace id`() = withDssApp(deps()) { client ->
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

  @Test
  fun `an unauthenticated monolith route is refused before the handler runs`() = withDssApp(deps()) { client ->
    val r = client.post(Paths.syncShipmentsWithFulfillments) {
      contentType(ContentType.Application.Json)
      setBody("""{"shopify_subdomain":"acme","shopify_order_id":1,"shipments":[]}""")
    }
    assert(r.status == HttpStatusCode.Unauthorized)
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
      withDssApp(deps(tokens = InMemoryShopTokenStore(mapOf(acmeShop to ADMIN_TOKEN)))) { client ->
        client.post(Paths.webhooksShopify) {
          header("X-Shopify-Topic", "orders/create")
          header("X-Shopify-Shop-Domain", acmeShop.normalizedShopifyHost)
          header("X-Shopify-Hmac-Sha256", base64HmacSha256(APP_SECRET, body.toByteArray(StandardCharsets.UTF_8)))
          setBody(body)
        }
        client.put(Paths.storesApiKey) {
          header("Authorization", "Bearer $MONOLITH_TO_DSS_API_KEY")
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
    assert(MONOLITH_TO_DSS_API_KEY !in logged)
    assert(APP_SECRET !in logged)
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
        config = testConfig(monolithToDssApiKey = MONOLITH_TO_DSS_API_KEY, monolithBaseUrl = "http://localhost:$port"),
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
        config = testConfig(monolithToDssApiKey = MONOLITH_TO_DSS_API_KEY, monolithBaseUrl = "http://localhost:$port"),
        httpClient = testHttpClient(),
      )
      val minted = withDssAppReturning(deps) { client ->
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
   * still cold stays out of rotation, and a `200` from the moment it ends.
   */
  @Test
  fun `health answers 503 while the warm-up runs and 200 once it is done`() {
    val gate = CompletableDeferred<Unit>()
    val deps = deps()
    withDssApp(deps, warmUp = WarmUp(5.seconds) { gate.await() }) { client ->
      val warming = client.get(Paths.health)
      assert(warming.status == HttpStatusCode.ServiceUnavailable)
      assert(warming.body<JsonObject>()["status"]!!.jsonPrimitive.content == "warming_up")
      gate.complete(Unit)
      awaitReady(deps)
      val ready = client.get(Paths.health)
      assert(ready.status == HttpStatusCode.OK)
      assert(ready.body<JsonObject>()["status"]!!.jsonPrimitive.content == "ok")
    }
  }

  /** A warm-up that hangs must not keep a task out of rotation: the budget opens the gate, and the warm-up is cancelled. */
  @Test
  fun `a warm-up that outlives its budget opens the gate when the budget ends`() {
    val deps = deps()
    withDssApp(deps, warmUp = WarmUp(200.milliseconds) { awaitCancellation() }) { client ->
      startApplication()
      awaitReady(deps)
      assert(client.get(Paths.health).status == HttpStatusCode.OK)
    }
  }

  @Test
  fun `a warm-up that throws opens the gate`() {
    val deps = deps()
    withDssApp(deps, warmUp = WarmUp(5.seconds) { error("a bug in the warm-up") }) { client ->
      startApplication()
      awaitReady(deps)
      assert(client.get(Paths.health).status == HttpStatusCode.OK)
    }
  }

  /** Only `/health` is gated: a request that reaches a warming task is served. */
  @Test
  fun `every other route is served while the warm-up runs`() {
    val gate = CompletableDeferred<Unit>()
    withDssApp(deps(), warmUp = WarmUp(5.seconds) { gate.await() }) { client ->
      assert(client.get(Paths.api).status == HttpStatusCode.OK)
      gate.complete(Unit)
    }
  }

  /**
   * The gate is opened by a coroutine the module launched; a test that asserts the open state has to let it run. The
   * test engine starts the application on the first request, so a test that polls before making one starts it itself.
   */
  private suspend fun awaitReady(deps: DssDependencies) = withTimeout(5.seconds) {
    while (!deps.readiness.isReady) delay(5)
  }

  /** [withDssApp] answers `Unit`; this variant hands the block's answer back, for a value the test needs after the app stopped. */
  private fun <T> withDssAppReturning(deps: DssDependencies, block: suspend (io.ktor.client.HttpClient) -> T): T {
    var answer: T? = null
    withDssApp(deps, authenticateAsMonolith = true) { client -> answer = block(client) }
    return answer!!
  }

  // ---------- helpers ----------

  private fun deps(tokens: InMemoryShopTokenStore = InMemoryShopTokenStore()): DssDependencies =

    dssDependencies(
      config = testConfig(appClientSecret = APP_SECRET, monolithToDssApiKey = MONOLITH_TO_DSS_API_KEY),
      monolithService = FakeMonolithService(),
      shopTokens = tokens,
      shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = FakeShopifyGraphqlService(acmeShop)),
    )
}
