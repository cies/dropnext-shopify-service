package dropnext.dss.handler

import dropnext.dss.path.Paths
import dropnext.dss.testutil.fixture.TEST_MONOLITH_TO_DSS_API_KEY
import dropnext.dss.testutil.fixture.testConfig
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test


/**
 * The diagnostic endpoints, unauthenticated and reachable from anywhere the service is, so what they
 * must *not* say matters as much as what they do: `/api` renders a configuration summary, and a field
 * added there carelessly would publish a secret.
 */
class DiagnosticsHandlersTest {

  @Test
  fun `the index lists every route the service serves`() = withDssApp(testDependencies()) { client ->
    val body = client.get(Paths.index).bodyAsText()
    assert(Paths.health in body)
    assert(Paths.webhooksShopify in body)
    assert(Paths.syncShipmentsWithFulfillments in body)
    assert(Paths.trackingUpdate in body)
    assert(Paths.storesApiKey in body)
    assert(Paths.apiWebhooksRegister in body)
  }

  @Test
  fun `health answers ok and names the running version`() = withDssApp(testDependencies()) { client ->
    val r = client.get(Paths.health)
    assert(r.status == HttpStatusCode.OK)
    val body = r.body<JsonObject>()
    assert(body["status"]!!.jsonPrimitive.content == "ok")
    assert(body["version"]!!.jsonPrimitive.content == "test-version")
  }

  /** The base url is the next case's, under the key the monolith reads. */
  @Test
  fun `the api status summary reports ok and the bind address`() = withDssApp(testDependencies()) { client ->
    val r = client.get(Paths.api)
    assert(r.status == HttpStatusCode.OK)
    val body = r.body<JsonObject>()
    assert(body["status"]!!.jsonPrimitive.content == "ok")
    assert(body["bind"]!!.jsonPrimitive.content == "0.0.0.0:8080")
  }

  @Test
  fun `the api status summary answers the base url and the redirect path under snake_case keys`() = withDssApp(testDependencies()) { client ->
    val r = client.get(Paths.api)
    assert(r.status == HttpStatusCode.OK)
    val body = r.body<JsonObject>()
    assert(body["dss_base_url"]!!.jsonPrimitive.content == "https://dss.test")
    assert(body["oauth_redirect_path"]!!.jsonPrimitive.content == "/oauth/callback")
    assert("dssBaseUrl" !in body)
  }

  /**
   * The one assertion that has to be about absence: every secret the configuration holds, checked
   * against the body of the endpoint most likely to grow a field that leaks one.
   */
  @Test
  fun `the api status summary carries no secret`() {
    val config = testConfig(
      appClientSecret = "shpss_app_secret_value",
      dssToMonolithApiKey = "mono_api_key_value",
    )
    withDssApp(testDependencies(config = config)) { client ->
      val body = client.get(Paths.api).bodyAsText()
      assert("shpss_app_secret_value" !in body)
      assert("mono_api_key_value" !in body)
      assert(TEST_MONOLITH_TO_DSS_API_KEY !in body)
    }
  }

  @Test
  fun `the redirect url endpoint answers the url Shopify is configured to call back`() =
    withDssApp(testDependencies()) { client ->
      val r = client.get(Paths.apiRedirectUrl)
      assert(r.status == HttpStatusCode.OK)
      assert(r.bodyAsText() == "https://dss.test/oauth/callback")
    }
}
