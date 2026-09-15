package dropnext.dss.handler

import dropnext.dss.DssDependencies
import dropnext.dss.boot.config.Config
import dropnext.dss.boot.warmup.WarmUp
import dropnext.dss.dssDependencies
import dropnext.dss.lib.shopify.token.InMemoryShopTokenStore
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServiceFactory
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test


private val monolithToDssApiKey = "d".repeat(32)


/**
 * The diagnostic endpoints, unauthenticated and reachable from anywhere the service is, so what they
 * must *not* say matters as much as what they do: `/api` renders a configuration summary, and a field
 * added there carelessly would publish a secret.
 */
class DiagnosticsHandlersTest {

  @Test
  fun `the index lists every route the service serves`() = withDssApp(deps()) { client ->
    val body = client.get(Paths.index).bodyAsText()
    assert(Paths.health in body)
    assert(Paths.webhooksShopify in body)
    assert(Paths.syncShipmentsWithFulfillments in body)
    assert(Paths.trackingUpdate in body)
    assert(Paths.storesApiKey in body)
    assert(Paths.apiWebhooksRegister in body)
  }

  @Test
  fun `health answers ok and names the running version`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.health)
    assert(r.status == HttpStatusCode.OK)
    val body = r.bodyAsText()
    assert("\"status\":\"ok\"" in body)
    assert("\"version\":\"test-version\"" in body)
  }

  /** The same shape with another status and a `503`, so the load balancer's `200` matcher keeps a warming task out of rotation. */
  @Test
  fun `health answers 503 warming_up until the warm-up is done`() {
    val gate = CompletableDeferred<Unit>()
    withDssApp(deps(), warmUp = WarmUp(5.seconds) { gate.await() }) { client ->
      val r = client.get(Paths.health)
      assert(r.status == HttpStatusCode.ServiceUnavailable)
      val body = r.body<JsonObject>()
      assert(body["status"]!!.jsonPrimitive.content == "warming_up")
      assert(body["version"]!!.jsonPrimitive.content == "test-version")
      gate.complete(Unit)
    }
  }

  @Test
  fun `the api status summary reports the bind address and the base url`() = withDssApp(deps()) { client ->
    val r = client.get(Paths.api)
    assert(r.status == HttpStatusCode.OK)
    val body = r.bodyAsText()
    assert("\"status\":\"ok\"" in body)
    assert("0.0.0.0:8080" in body)
    assert("https://dss.test" in body)
  }

  /**
   * The one assertion that has to be about absence: every secret the configuration holds, checked
   * against the body of the endpoint most likely to grow a field that leaks one.
   */
  @Test
  fun `the api status summary carries no secret`() {
    val config = testConfig(
      appClientSecret = "shpss_app_secret_value",
      monolithToDssApiKey = monolithToDssApiKey,
      dssToMonolithApiKey = "mono_api_key_value",
    )
    withDssApp(deps(config = config)) { client ->
      val body = client.get(Paths.api).bodyAsText()
      assert("shpss_app_secret_value" !in body)
      assert("mono_api_key_value" !in body)
      assert(monolithToDssApiKey !in body)
    }
  }

  @Test
  fun `the redirect url endpoint answers the url Shopify is configured to call back`() =
    withDssApp(deps()) { client ->
      val r = client.get(Paths.apiRedirectUrl)
      assert(r.status == HttpStatusCode.OK)
      assert(r.bodyAsText() == "https://dss.test/oauth/callback")
    }

  // ---------- helpers ----------

  /** Diagnostics look nothing up for a shop; the fakes only keep the graph from building clients that reach out. */
  private fun deps(config: Config = testConfig(monolithToDssApiKey = monolithToDssApiKey)): DssDependencies {
    val tokens = InMemoryShopTokenStore()
    return dssDependencies(
      config = config,
      monolithService = FakeMonolithService(),
      shopTokens = tokens,
      shopifyGraphqlServiceFactory = FakeShopifyGraphqlServiceFactory(service = FakeShopifyGraphqlService(), tokens = tokens),
    )
  }
}
