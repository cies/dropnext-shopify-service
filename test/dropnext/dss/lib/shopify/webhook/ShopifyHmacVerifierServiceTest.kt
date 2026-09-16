package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.testutil.fixture.TEST_APP_SECRET
import dropnext.dss.testutil.helper.base64HmacSha256
import dropnext.dss.testutil.helper.hexHmacSha256
import io.ktor.http.parametersOf
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Test


class ShopifyHmacVerifierServiceTest {

  private val signatures = ShopifyHmacVerifierService(ShopifyAppSecret(TEST_APP_SECRET))

  @Test
  fun `verifyWebhook accepts a correct base64 hmac`() {
    val body = """{"id":1001,"name":"#1001"}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256(TEST_APP_SECRET, body)
    assert(signatures.verifyWebhook(hmac, body))
  }

  @Test
  fun `verifyWebhook rejects a tampered body`() {
    val body = """{"id":1001}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256(TEST_APP_SECRET, body)
    val tampered = """{"id":1002}""".toByteArray(StandardCharsets.UTF_8)
    assert(!signatures.verifyWebhook(hmac, tampered))
  }

  @Test
  fun `verifyWebhook rejects wrong secret`() {
    val body = """{"id":1}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256("other-secret", body)
    assert(!signatures.verifyWebhook(hmac, body))
  }

  @Test
  fun `verifyWebhook rejects null or blank hmac header`() {
    val body = """{"x":1}""".toByteArray(StandardCharsets.UTF_8)
    assert(!signatures.verifyWebhook(null, body))
    assert(!signatures.verifyWebhook("", body))
    assert(!signatures.verifyWebhook("   ", body))
  }

  @Test
  fun `verifyWebhook rejects malformed base64`() {
    val body = """{"x":1}""".toByteArray(StandardCharsets.UTF_8)
    assert(!signatures.verifyWebhook("!!!not base64!!!", body))
  }

  /** The delivery the warm-up sends itself is signed here and checked by [verifyWebhook]: a change to either side must not part them. */
  @Test
  fun `signWebhook produces the signature Shopify would, and verifyWebhook accepts it`() {
    val body = """{"id":0,"admin_graphql_api_id":"gid://shopify/Order/0"}""".toByteArray(StandardCharsets.UTF_8)
    val signature = signatures.signWebhook(body)
    assert(signature == base64HmacSha256(TEST_APP_SECRET, body))
    assert(signatures.verifyWebhook(signature, body))
  }

  @Test
  fun `verifyOAuthCallback accepts a correctly-signed query`() {
    val params = parametersOf(
      "shop" to listOf("acme.myshopify.com"),
      "code" to listOf("abc123"),
      "timestamp" to listOf("1700000000"),
    )
    val expectedMessage = "code=abc123&shop=acme.myshopify.com&timestamp=1700000000"
    val hmac = hexHmacSha256(TEST_APP_SECRET, expectedMessage)
    assert(signatures.verifyOAuthCallback(params, hmac))
  }

  @Test
  fun `verifyOAuthCallback excludes hmac and signature params from the canonical string`() {
    val params = parametersOf(
      "shop" to listOf("acme.myshopify.com"),
      "code" to listOf("abc"),
      "hmac" to listOf("ignored"),
      "signature" to listOf("ignored-too"),
    )
    val canonical = "code=abc&shop=acme.myshopify.com"
    val hmac = hexHmacSha256(TEST_APP_SECRET, canonical)
    assert(signatures.verifyOAuthCallback(params, hmac))
  }

  /**
   * The example from Shopify's OAuth documentation, signed with the secret `hush`. Every other case here signs the
   * canonical string this test writes out by hand, so only Shopify's own vector proves the canonicalisation is Shopify's.
   */
  @Test
  fun `verifyOAuthCallback accepts the signed example from Shopify's documentation`() {
    val hmac = "700e2dadb827fcc8609e9d5ce208b2e9cdaab9df07390d2cbca10d7c328fc4bf"
    val params = parametersOf(
      "code" to listOf("0907a61c0c8d55e99db179b68161bc00"),
      "hmac" to listOf(hmac),
      "shop" to listOf("some-shop.myshopify.com"),
      "state" to listOf("0.6784241404160823"),
      "timestamp" to listOf("1337178173"),
    )
    assert(ShopifyHmacVerifierService(ShopifyAppSecret("hush")).verifyOAuthCallback(params, hmac))
  }

  @Test
  fun `verifyOAuthCallback rejects a wrong hmac`() {
    val params = parametersOf("shop" to listOf("acme.myshopify.com"))
    assert(!signatures.verifyOAuthCallback(params, "00".repeat(32)))
  }

  @Test
  fun `verifyOAuthCallback hmac matching is case insensitive`() {
    val params = parametersOf("shop" to listOf("acme.myshopify.com"))
    val canonical = "shop=acme.myshopify.com"
    val lower = hexHmacSha256(TEST_APP_SECRET, canonical)
    val upper = lower.uppercase()
    assert(signatures.verifyOAuthCallback(params, upper))
  }
}
