package dropnext.dss.boot.warmup

import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.ktor.MONOLITH_WEBHOOK_AUTH_REALM
import dropnext.dss.lib.ktor.TRACE_ID_HEADER
import dropnext.dss.lib.shopify.webhook.ShopifyHmacVerifierService
import dropnext.dss.path.Paths
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.content.ByteArrayContent
import java.io.IOException


/** The `X-Shopify-Webhook-Id` of the delivery the service sends itself: fixed, so it never reads as a redelivery of a shop's webhook. */
const val WARM_UP_WEBHOOK_ID = "warm-up"

/**
 * [WarmUpLoopbackService] over the shared client, to `http://127.0.0.1:<port>`: plain HTTP on the port the server
 * itself listens on, never through the load balancer, which is not attached yet while this runs. The bearer key and
 * the webhook signature travel only over that. No answer within the client's timeouts is `null`, and nothing here
 * retries: the point is to run the pipeline once, not to make it work.
 */
class HttpWarmUpLoopbackService(
  private val httpClient: HttpClient,
  port: Int,
  private val monolithToDssApiKey: MonolithToDssApiKey,
  private val hmacVerifier: ShopifyHmacVerifierService,
) : WarmUpLoopbackService {

  private val origin = "http://127.0.0.1:$port"

  override suspend fun shopifyStoreStatus(shop: ShopDomain, traceId: String): LoopbackAnswer? {
    val response = responseOrNull {
      httpClient.get("$origin${Paths.storesShopifyStatus}") {
        parameter("shopify_subdomain", shop.subdomainOnly)
        header(HttpHeaders.Authorization, "Bearer ${monolithToDssApiKey.value}")
        header(TRACE_ID_HEADER, traceId)
      }
    } ?: return null
    val status = response.status.value
    val challenge = response.headers[HttpHeaders.WWWAuthenticate]
    return LoopbackAnswer(status, bearerRejected = isOurBearerChallenge(status, challenge))
  }

  override suspend fun webhookDelivery(shop: ShopDomain, traceId: String): Int? {
    // An id-only delivery for `orders/updated`, a topic the service does not subscribe to: the handler acknowledges it
    // without loading anything, so the ids need not exist. The signature is over these exact bytes.
    val body = """{"id":0,"admin_graphql_api_id":"gid://shopify/Order/0"}""".toByteArray(Charsets.UTF_8)
    val response = responseOrNull {
      httpClient.post("$origin${Paths.webhooksShopify}") {
        header("X-Shopify-Topic", "orders/updated")
        header("X-Shopify-Shop-Domain", shop.normalizedShopifyHost)
        header("X-Shopify-Webhook-Id", WARM_UP_WEBHOOK_ID)
        header("X-Shopify-Hmac-Sha256", hmacVerifier.signWebhook(body))
        header(TRACE_ID_HEADER, traceId)
        setBody(ByteArrayContent(body, ContentType.Application.Json))
      }
    }
    return response?.status?.value
  }

  /** A refused connection, a reset and a timeout are all "no answer"; anything else is a bug and propagates. */
  private suspend fun responseOrNull(request: suspend () -> HttpResponse): HttpResponse? =
    try {
      request()
    } catch (_: IOException) {
      null
    }
}

/** The bearer provider's refusal: a `401` whose challenge names our realm. The handlers' own `401`s carry no challenge. */
internal fun isOurBearerChallenge(status: Int, wwwAuthenticate: String?): Boolean {
  if (status != 401 || wwwAuthenticate == null) return false
  // Parsed rather than matched: the realm is quoted only when it has to be, so either spelling may arrive.
  val challenge = runCatching { parseAuthorizationHeader(wwwAuthenticate) }.getOrNull() as? HttpAuthHeader.Parameterized
    ?: return false
  return challenge.authScheme.equals(AuthScheme.Bearer, ignoreCase = true) &&
    challenge.parameter("realm") == MONOLITH_WEBHOOK_AUTH_REALM
}
