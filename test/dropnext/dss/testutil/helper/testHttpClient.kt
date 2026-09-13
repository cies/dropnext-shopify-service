package dropnext.dss.testutil.helper

import dropnext.dss.boot.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import java.util.concurrent.TimeUnit


/**
 * The one client the tests talk to their fake servers with. Timeouts are seconds rather than the
 * production minutes: a fake that never answers should fail its test quickly instead of parking the
 * suite until Gradle gives up.
 *
 * [followRedirects] is off for the OAuth tests, which assert on the `302` itself.
 */
fun testHttpClient(followRedirects: Boolean = true): HttpClient = HttpClient(OkHttp) {
  this.followRedirects = followRedirects
  engine {
    config {
      connectTimeout(2, TimeUnit.SECONDS)
      readTimeout(5, TimeUnit.SECONDS)
      writeTimeout(5, TimeUnit.SECONDS)
    }
  }
  install(HttpTimeout) {
    requestTimeoutMillis = 5_000
    connectTimeoutMillis = 2_000
    socketTimeoutMillis = 5_000
  }
}

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
