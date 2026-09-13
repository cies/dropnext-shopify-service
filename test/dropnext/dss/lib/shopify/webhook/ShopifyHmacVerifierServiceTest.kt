package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopifyAppSecret
import io.ktor.http.parametersOf
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test

class ShopifyHmacVerifierServiceTest {

  private val secret = "shpss_test_secret"
  private val signatures = ShopifyHmacVerifierService(ShopifyAppSecret(secret))

  @Test
  fun `verifyWebhook accepts a correct base64 hmac`() {
    val body = """{"id":1001,"name":"#1001"}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256(secret, body)
    assert(signatures.verifyWebhook(hmac, body))
  }

  @Test
  fun `verifyWebhook rejects a tampered body`() {
    val body = """{"id":1001}""".toByteArray(StandardCharsets.UTF_8)
    val hmac = base64HmacSha256(secret, body)
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
    assert(signature == base64HmacSha256(secret, body))
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
    val hmac = hexHmacSha256(secret, expectedMessage)
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
    val hmac = hexHmacSha256(secret, canonical)
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
    val lower = hexHmacSha256(secret, canonical)
    val upper = lower.uppercase()
    assert(signatures.verifyOAuthCallback(params, upper))
  }

  private fun base64HmacSha256(secret: String, body: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal(body))
  }

  private fun hexHmacSha256(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
