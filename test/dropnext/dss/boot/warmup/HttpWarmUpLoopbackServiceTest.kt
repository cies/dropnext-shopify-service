package dropnext.dss.boot.warmup

import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.testHttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


private val acmeShop = ShopDomain.parse("acme.myshopify.com")!!
private const val APP_SECRET = "shpss_app_secret"
private val BEARER = "m".repeat(32)


/**
 * The two requests the service sends itself, at the wire: they have to pass the same bearer provider and the same
 * HMAC check a real caller passes, so what matters is the exact headers and the signature over the exact bytes sent.
 * The recording monolith fake stands in for our own server here; it records any request on any path.
 */
class HttpWarmUpLoopbackServiceTest {

  @Test
  fun `apiCheck asks for the shop with the monolith's bearer and the trace id, and answers the status`() {
    val server = FakeMonolithHttpServer()
    val port = server.start()
    try {
      server.enqueue(HttpStatusCode.Unauthorized, """{"error":"missing Shopify Admin token"}""")
      val status = runBlocking { service(port).apiCheck(acmeShop, "trace-warm-up") }
      assert(status == 401)
      val request = server.requests.single()
      assert(request.method == "GET")
      assert(request.path == Paths.apiCheck)
      assert(request.query["shop"] == listOf("acme.myshopify.com"))
      assert(request.authorization() == "Bearer $BEARER")
      assert(request.headers["X-Trace-Id"] == listOf("trace-warm-up"))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `webhookDelivery is an orders-updated delivery signed over the bytes it sends`() {
    val server = FakeMonolithHttpServer()
    val port = server.start()
    try {
      server.enqueue(HttpStatusCode.OK, """{"outcome":"skipped"}""")
      val status = runBlocking { service(port).webhookDelivery(acmeShop, "trace-warm-up") }
      assert(status == 200)
      val request = server.requests.single()
      assert(request.method == "POST")
      assert(request.path == Paths.webhooksShopify)
      assert(request.contentType()?.startsWith("application/json") == true)
      assert(request.headers["X-Shopify-Topic"] == listOf("orders/updated"))
      assert(request.headers["X-Shopify-Shop-Domain"] == listOf("acme.myshopify.com"))
      assert(request.headers["X-Shopify-Webhook-Id"] == listOf(WARM_UP_WEBHOOK_ID))
      assert(request.headers["X-Trace-Id"] == listOf("trace-warm-up"))
      assert(request.body.isNotBlank())
      assert(request.headers["X-Shopify-Hmac-Sha256"] == listOf(base64HmacSha256(APP_SECRET, request.body.toByteArray())))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a request that gets no answer is null rather than an exception`() {
    FakeFlakyServer().use { upstream ->
      val port = upstream.baseUrl.substringAfterLast(':').toInt()
      val status = runBlocking { service(port).apiCheck(acmeShop, "trace-warm-up") }
      assert(status == null)
      assert(upstream.connectionCount >= 1)
    }
  }

  // ---------- helpers ----------

  private fun service(port: Int) = HttpWarmUpLoopbackService(
    httpClient = testHttpClient(),
    port = port,
    monolithToDssApiKey = MonolithToDssApiKey(BEARER),
    hmacVerifier = ShopifyHmacVerifierService(ShopifyAppSecret(APP_SECRET)),
  )
}
