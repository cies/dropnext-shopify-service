package dropnext.dss.boot.warmup

import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.path.Paths
import dropnext.dss.testutil.fake.FakeFlakyServer
import dropnext.dss.testutil.fake.FakeMonolithHttpServer
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.TEST_APP_SECRET
import dropnext.dss.testutil.fixture.testDependencies
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.dss.testutil.helper.withDssApp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


private val BEARER = "m".repeat(32)


/**
 * The two requests the service sends itself, at the wire: they have to pass the same bearer provider and the same
 * HMAC check a real caller passes, so what matters is the exact headers and the signature over the exact bytes sent,
 * and for the bearer check, which of the two `401`s came back. The recording monolith fake stands in for our own
 * server here; it records any request on any path.
 */
class HttpWarmUpLoopbackServiceTest {

  @Test
  fun `shopifyStoreStatus asks for the shop with the monolith's bearer and the trace id, and answers the status`() {
    val server = FakeMonolithHttpServer()
    val port = server.start()
    try {
      server.enqueue(HttpStatusCode.Unauthorized, """{"error":"missing Shopify Admin token"}""")
      val answer = runBlocking { service(port).shopifyStoreStatus(ACME_SHOP, "trace-warm-up") }
      assert(answer == LoopbackAnswer(401, bearerRejected = false))
      val request = server.requests.single()
      assert(request.method == "GET")
      assert(request.path == Paths.storesShopifyStatus)
      assert(request.query["shopify_subdomain"] == listOf("acme"))
      assert(request.authorization() == "Bearer $BEARER")
      assert(request.headers["X-Trace-Id"] == listOf("trace-warm-up"))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `shopifyStoreStatus reads a bare 401 with our realm's challenge as a rejected bearer`() {
    val server = FakeMonolithHttpServer()
    val port = server.start()
    try {
      server.enqueue(HttpStatusCode.Unauthorized, "", headers = mapOf(HttpHeaders.WWWAuthenticate to "Bearer realm=\"dss-internal\""))
      val answer = runBlocking { service(port).shopifyStoreStatus(ACME_SHOP, "trace-warm-up") }
      assert(answer == LoopbackAnswer(401, bearerRejected = true))
    } finally {
      server.stop()
    }
  }

  /**
   * The two `401`s exactly as the production module answers them, replayed to the client: what the check reads is what
   * the bearer provider and the handler really send, not what this test assumes they send.
   */
  @Test
  fun `shopifyStoreStatus tells the bearer provider's 401 from the handler's, as the production module answers them`() {
    val deps = testDependencies()
    val answers = withDssApp(deps) { client ->
      val path = "${Paths.storesShopifyStatus}?shopify_subdomain=${ACME_SHOP.subdomainOnly}"
      val refused = client.get(path) { header(HttpHeaders.Authorization, "Bearer ${"w".repeat(32)}") }
      val noToken = client.get(path) { header(HttpHeaders.Authorization, "Bearer ${deps.config.monolithToDssApiKey.value}") }
      listOf(refused.replayable(), noToken.replayable())
    }
    val server = FakeMonolithHttpServer()
    val port = server.start()
    try {
      answers.forEach { answer -> server.enqueue(answer.status, "", answer.headers) }
      val refused = runBlocking { service(port).shopifyStoreStatus(ACME_SHOP, "trace-warm-up") }
      val noToken = runBlocking { service(port).shopifyStoreStatus(ACME_SHOP, "trace-warm-up") }
      assert(refused == LoopbackAnswer(401, bearerRejected = true))
      assert(noToken == LoopbackAnswer(401, bearerRejected = false))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a challenge for another realm or on another status is not ours`() {
    assert(isOurBearerChallenge(401, "Bearer realm=\"dss-internal\""))
    assert(isOurBearerChallenge(401, "Bearer realm=dss-internal"))
    assert(!isOurBearerChallenge(401, "Basic realm=dss-internal"))
    assert(!isOurBearerChallenge(401, "Bearer realm=\"elsewhere\""))
    assert(!isOurBearerChallenge(403, "Bearer realm=\"dss-internal\""))
    assert(!isOurBearerChallenge(401, null))
  }

  @Test
  fun `webhookDelivery is an orders-updated delivery signed over the bytes it sends`() {
    val server = FakeMonolithHttpServer()
    val port = server.start()
    try {
      server.enqueue(HttpStatusCode.OK, """{"outcome":"skipped"}""")
      val status = runBlocking { service(port).webhookDelivery(ACME_SHOP, "trace-warm-up") }
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
      assert(request.headers["X-Shopify-Hmac-Sha256"] == listOf(base64HmacSha256(TEST_APP_SECRET, request.body.toByteArray())))
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a request that gets no answer is null rather than an exception`() {
    FakeFlakyServer().use { upstream ->
      val port = upstream.baseUrl.substringAfterLast(':').toInt()
      val answer = runBlocking { service(port).shopifyStoreStatus(ACME_SHOP, "trace-warm-up") }
      assert(answer == null)
      assert(upstream.connectionCount >= 1)
    }
  }

  // ---------- helpers ----------

  private fun service(port: Int) = HttpWarmUpLoopbackService(
    httpClient = testHttpClient(),
    port = port,
    monolithToDssApiKey = MonolithToDssApiKey(BEARER),
    hmacVerifier = ShopifyHmacVerifierService(ShopifyAppSecret(TEST_APP_SECRET)),
  )

  /** An answer of the production module, kept as the parts the check reads: the status and the challenge, not the body. */
  private data class Replayable(val status: HttpStatusCode, val headers: Map<String, String>)

  private fun HttpResponse.replayable(): Replayable {
    val challenge = headers[HttpHeaders.WWWAuthenticate]
    return Replayable(status, challenge?.let { mapOf(HttpHeaders.WWWAuthenticate to it) }.orEmpty())
  }
}
