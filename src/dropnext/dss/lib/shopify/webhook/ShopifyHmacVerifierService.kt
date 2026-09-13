package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.lib.crypto.constantTimeEquals
import dropnext.dss.lib.crypto.hmacSha256
import io.ktor.http.Parameters
import java.util.Base64


/**
 * Verifies Shopify-issued HMAC signatures on inbound traffic. Binds the app's client secret
 * once at construction so callers don't thread it through every verification call.
 *
 * Stateless aside from the injected [clientSecret], so a single instance is safe to share
 * across requests.
 */
class ShopifyHmacVerifierService(private val clientSecret: ShopifyAppSecret) {

  fun verifyOAuthCallback(params: Parameters, hmacHex: String): Boolean {
    // Shopify: remove hmac/signature, sort by key, join with "&" as in query string (see OAuth docs).
    val message = params.entries()
      .asSequence()
      .filter { it.key != "hmac" && it.key != "signature" }
      .sortedBy { it.key }
      .joinToString("&") { e ->
        val value = e.value.singleOrNull() ?: e.value.firstOrNull().orEmpty()
        "${e.key}=$value"
      }
    val providedBytes = hexToBytesOrNull(hmacHex) ?: return false
    return constantTimeEquals(hmacSha256(clientSecret.value, message), providedBytes)
  }

  /**
   * The mirror image of [verifyWebhook]: the `X-Shopify-Hmac-Sha256` Shopify would put on [rawBody]. For the delivery
   * the warm-up sends the service itself, which has to pass the check above like any other.
   */
  fun signWebhook(rawBody: ByteArray): String =
    Base64.getEncoder().encodeToString(hmacSha256(clientSecret.value, rawBody))

  fun verifyWebhook(hmacHeader: String?, rawBody: ByteArray): Boolean {
    if (hmacHeader.isNullOrBlank()) return false
    val decoded = try {
      Base64.getDecoder().decode(hmacHeader.trim())
    } catch (_: IllegalArgumentException) {
      return false
    }
    return constantTimeEquals(hmacSha256(clientSecret.value, rawBody), decoded)
  }

  /** Decodes an ASCII hex string to bytes, returning `null` if it is malformed. */
  private fun hexToBytesOrNull(hex: String): ByteArray? {
    val s = hex.trim()
    if (s.length % 2 != 0 || s.isEmpty()) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
      val hi = Character.digit(s[i * 2], 16)
      val lo = Character.digit(s[i * 2 + 1], 16)
      if (hi < 0 || lo < 0) return null
      out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
  }
}
