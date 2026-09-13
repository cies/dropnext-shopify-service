package dropnext.dss.boot.warmup

import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.domain.ShopDomain
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

  override suspend fun apiCheck(shop: ShopDomain, traceId: String): Int? = statusOrNull {
    httpClient.get("$origin${Paths.apiCheck}") {
      parameter("shop", shop.normalizedShopifyHost)
      header(HttpHeaders.Authorization, "Bearer ${monolithToDssApiKey.value}")
      header(TRACE_ID_HEADER, traceId)
    }
  }

  override suspend fun webhookDelivery(shop: ShopDomain, traceId: String): Int? {
    // The shape of an `orders/updated` delivery with the fields the subscription projects. The handler acknowledges
    // the topic without loading anything, so the ids need not exist; the signature is over these exact bytes.
    val body = """{"id":0,"admin_graphql_api_id":"gid://shopify/Order/0"}""".toByteArray(Charsets.UTF_8)
    return statusOrNull {
      httpClient.post("$origin${Paths.webhooksShopify}") {
        header("X-Shopify-Topic", "orders/updated")
        header("X-Shopify-Shop-Domain", shop.normalizedShopifyHost)
        header("X-Shopify-Webhook-Id", WARM_UP_WEBHOOK_ID)
        header("X-Shopify-Hmac-Sha256", hmacVerifier.signWebhook(body))
        header(TRACE_ID_HEADER, traceId)
        setBody(ByteArrayContent(body, ContentType.Application.Json))
      }
    }
  }

  /** A refused connection, a reset and a timeout are all "no answer"; anything else is a bug and propagates. */
  private suspend fun statusOrNull(request: suspend () -> HttpResponse): Int? =
    try {
      request().status.value
    } catch (_: IOException) {
      null
    }
}
