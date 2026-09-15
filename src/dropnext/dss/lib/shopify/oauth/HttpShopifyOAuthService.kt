package dropnext.dss.lib.shopify.oauth

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.ShopifyAppSecret
import dropnext.dss.lib.crypto.constantTimeEquals
import dropnext.dss.lib.crypto.hmacSha256
import dropnext.dss.lib.json.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable


/** TTL for the OAuth `state` parameter. Five minutes is plenty for a single installation round-trip. */
private const val OAUTH_STATE_TTL_SECONDS = 300L

/**
 * The [ShopifyOAuthService] that talks to Shopify. The `state` is an HMAC over `shop|expiry|nonce` with the
 * app secret, so it needs no storage: a callback is trusted when its state verifies and has not expired.
 *
 * Stateless aside from the injected [HttpClient] and the app's credentials, so a single instance
 * is safe to share across requests.
 */
class HttpShopifyOAuthService(
  private val httpClient: HttpClient,
  private val clientId: String,
  private val clientSecret: ShopifyAppSecret,
  private val redirectUrl: String,
) : ShopifyOAuthService {

  override fun authorizeUrl(shop: ShopDomain, state: String): String {
    val enc: (String) -> String = { URLEncoder.encode(it, StandardCharsets.UTF_8) }
    return buildString {
      append("https://")
      append(shop.normalizedShopifyHost)
      append(OutBoundShopifyOAuthPaths.adminOAuthAuthorize)
      append("?client_id=").append(enc(clientId))
      append("&scope=").append(enc(ShopifyAccessScope.entries.joinToString(",") { it.handle }))
      append("&redirect_uri=").append(enc(redirectUrl))
      append("&state=").append(enc(state))
    }
  }

  /**
   * A freshly signed `shop|expiry|nonce|hmac` state token. The expiry pins the validity window so a
   * leaked redirect cannot be replayed after [OAUTH_STATE_TTL_SECONDS].
   */
  override fun signedState(shop: ShopDomain, now: Instant): String {
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val noncePart = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
    val expiresAt = now.epochSecond + OAUTH_STATE_TTL_SECONDS
    val payload = "${shop.normalizedShopifyHost}|$expiresAt|$noncePart"
    return "$payload|${sign(payload)}"
  }

  override fun isSignedStateValid(
    state: String,
    expectedShop: ShopDomain,
    now: Instant,
  ): Boolean {
    val parts = state.split('|')
    if (parts.size != 4) return false
    val shop = parts[0]
    val expiresAt = parts[1].toLongOrNull() ?: return false
    val nonce = parts[2]
    val signature = parts[3]
    if (shop != expectedShop.normalizedShopifyHost) return false
    if (expiresAt < now.epochSecond) return false
    return constantTimeEquals(sign("$shop|$expiresAt|$nonce"), signature)
  }

  /**
   * A refused code is a `4xx` with a JSON error body; the status is checked before the body is decoded
   * as a token, because decoding that body used to make a refusal read as a network failure.
   *
   * Only an [IOException] is a transport failure. Anything else thrown here is a bug, and answering it as
   * "Shopify did not answer" would send the merchant back to Shopify for an install that fails the same way
   * again; it propagates to `StatusPages` and its `500` instead.
   */
  override suspend fun exchangeCode(shop: ShopDomain, code: String): OAuthResult<ShopifyAccessGrant> {
    val url = "https://${shop.normalizedShopifyHost}${OutBoundShopifyOAuthPaths.adminOAuthAccessToken}"
    return try {
      val response = httpClient.post(url) {
        contentType(ContentType.Application.Json)
        setBody(OAuthAccessTokenRequest(clientId = clientId, clientSecret = clientSecret.value, code = code))
      }
      val status = response.status.value
      when {
        response.status.isSuccess() -> tokenFrom(response)
        status in 400..499 -> Failure(OAuthError.CodeRejected(status))
        else -> Failure(OAuthError.Transport("Shopify answered HTTP $status"))
      }
    } catch (e: IOException) {
      Failure(OAuthError.Transport(e.message ?: "OAuth code exchange failed"))
    }
  }

  /**
   * The token and the granted scopes out of a `2xx`. A body that does not decode is answered without the decoder's
   * complaint: that quotes the body it could not read, and this body carries the access token, which must never reach
   * a log line.
   */
  private suspend fun tokenFrom(response: HttpResponse): OAuthResult<ShopifyAccessGrant> {
    val body = response.bodyAsText()
    return try {
      val decoded = AppJson.decodeFromString(OAuthAccessTokenResponse.serializer(), body)
      val scopeHandles = decoded.scope.split(',').map { it.trim() }.filter { it.isNotEmpty() }
      Success(ShopifyAccessGrant(ShopifyAdminToken(decoded.accessToken), scopeHandles))
    } catch (_: SerializationException) {
      Failure(OAuthError.Transport("Shopify's token response was not the expected JSON"))
    }
  }

  private fun sign(payload: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(clientSecret.value, payload))
}

@Serializable
private data class OAuthAccessTokenRequest(
  @SerialName("client_id")
  val clientId: String,

  @SerialName("client_secret")
  val clientSecret: String,

  @SerialName("code")
  val code: String,
)

/** The wire shape of Shopify's token response; the token is wrapped in a [ShopifyAdminToken] as soon as it is decoded. */
@Serializable
private data class OAuthAccessTokenResponse(
  @SerialName("access_token")
  val accessToken: String,

  @SerialName("scope")
  val scope: String,
)
