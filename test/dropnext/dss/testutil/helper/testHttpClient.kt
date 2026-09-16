package dropnext.dss.testutil.helper

import dropnext.dss.boot.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import java.util.concurrent.TimeUnit


/**
 * The one client the tests talk to their fake servers with.
 *
 * The timeouts are a backstop against a fake that never answers, not a deadline a test asserts on: a test *about* a
 * timeout passes its own, short one. Generous, because they are not measuring the fake — they were five seconds, and
 * under the JaCoCo agent a cold JVM spent longer than that loading the client and the server on a loopback request
 * that answers in milliseconds once warm, which failed two tests for a reason that had nothing to do with them.
 * See [TestSuiteWarmUp], which removes most of that cost; this is what is left if it ever cannot run.
 */
fun testHttpClient(followRedirects: Boolean = true): HttpClient = HttpClient(OkHttp) {
  this.followRedirects = followRedirects
  engine {
    config {
      connectTimeout(TEST_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      readTimeout(TEST_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      writeTimeout(TEST_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
  }
  install(HttpTimeout) {
    requestTimeoutMillis = TEST_CLIENT_TIMEOUT_SECONDS * 1_000
    connectTimeoutMillis = TEST_CLIENT_TIMEOUT_SECONDS * 1_000
    socketTimeoutMillis = TEST_CLIENT_TIMEOUT_SECONDS * 1_000
  }
}

/** Long enough that a loaded machine never trips it, short enough that a hung fake fails its test instead of the run. */
const val TEST_CLIENT_TIMEOUT_SECONDS: Long = 30

/**
 * A client whose every request fails with [cause] before it reaches the wire, for proving that a failure which is
 * not a transport failure propagates as the bug it is instead of being answered as a network failure.
 */
fun throwingHttpClient(cause: Throwable): HttpClient = HttpClient(OkHttp) {
  install(createClientPlugin("ThrowOnRequest") { onRequest { _, _ -> throw cause } })
}

/**
 * The Admin Graphql endpoint of a fake Shopify server on [port]. Derived from the configured API
 * version rather than spelled out, so bumping the version does not break a test that has no opinion
 * about it.
 */
fun shopifyGraphqlUrl(port: Int, apiVersion: String = Config.SHOPIFY_API_VERSION): String =
  "http://localhost:$port/admin/api/$apiVersion/graphql.json"
