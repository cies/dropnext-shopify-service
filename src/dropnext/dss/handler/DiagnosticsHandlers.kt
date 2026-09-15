package dropnext.dss.handler

import dropnext.dss.boot.config.Config
import dropnext.dss.boot.warmup.Readiness
import dropnext.dss.path.Paths
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/** Handlers for the diagnostic/health endpoints (`/`, `/health`, `/api`, `/api/redirect-url`), all unauthenticated. */
class DiagnosticsHandlers(
  private val dssConfig: Config,
  private val readiness: Readiness,
) {
  suspend fun handleIndex(call: ApplicationCall) {
    call.respondText(
      """
      API is running.

      --- Infrastructure / diagnostics ---
        GET  ${Paths.index.padEnd(26)}This index
        GET  ${Paths.health.padEnd(26)}Health probe - JSON with status "ok" and the running version; 503 with status "warming_up" until the warm-up is done
        GET  ${Paths.api.padEnd(26)}JSON diagnostic info (config, URLs, issues)
        GET  ${Paths.apiRedirectUrl.padEnd(26)}Full OAuth redirect URL

      --- Webhook subscriptions (Authorization: Bearer <MONOLITH_TO_DSS_API_KEY> required) ---
        GET  ${Paths.apiCheck.padEnd(20)}?shop=  Readiness for a specific shop: token resolvable, access scopes, webhook subscriptions
        POST ${Paths.apiWebhooksRegister}?shop=  Register a shop's webhook subscriptions as an install does, deleting those for topics no longer handled

      --- Shopify OAuth ---
        GET  ${Paths.install.padEnd(20)}?shop=  Start OAuth - redirects to Shopify authorize URL
        GET  ${dssConfig.oauthRedirectPath.padEnd(26)}OAuth callback - code exchange, saves token, registers webhooks

      --- Webhooks ---
        POST ${Paths.webhooksShopify.padEnd(26)}Shopify webhook receiver (products/*, orders/*)

      --- DSS Internal API (Authorization: Bearer <MONOLITH_TO_DSS_API_KEY> required) ---
        PUT  ${Paths.storesApiKey.padEnd(40)} Set Shopify Admin token (see the checked-in monolith contract)
        POST ${Paths.syncShipmentsWithFulfillments.padEnd(40)} Monolith webhook: SyncShipmentsWithFulfillmentsRequest -> sync Shopify fulfillments
        POST ${Paths.trackingUpdate.padEnd(40)} Monolith webhook: TrackingUpdateRequest -> Shopify FulfillmentEvent
      """.trimIndent(),
      ContentType.Text.Plain,
      HttpStatusCode.OK,
    )
  }

  suspend fun handleApiStatus(call: ApplicationCall) {
    call.respond(
      ApiStatusResponse(
        status = "ok",
        version = dssConfig.versionTag,
        bind = "0.0.0.0:${dssConfig.serverPort}",
        dssBaseUrl = dssConfig.dssBaseUrl,
        oauthRedirectPath = dssConfig.oauthRedirectPath,
      ),
    )
  }

  /**
   * The same shape as the monolith's `/health`, so one probe reads both services. A `503` until the warm-up is done:
   * the load balancer's matcher is `200`, so a task that is still cold stays out of rotation, and the task it replaces
   * keeps serving meanwhile. Only this route is gated; a request that reaches a warming task is served.
   */
  suspend fun handleHealth(call: ApplicationCall) {
    if (!readiness.isReady) {
      return call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "warming_up", version = dssConfig.versionTag))
    }
    call.respond(HealthResponse(status = "ok", version = dssConfig.versionTag))
  }

  suspend fun handleRedirectUrl(call: ApplicationCall) {
    call.respondText(dssConfig.redirectUrl, ContentType.Text.Plain, HttpStatusCode.OK)
  }
}


@Serializable
private data class HealthResponse(val status: String, val version: String)

@Serializable
private data class ApiStatusResponse(
  val status: String,

  val version: String,

  val bind: String,

  @SerialName("dss_base_url")
  val dssBaseUrl: String,

  @SerialName("oauth_redirect_path")
  val oauthRedirectPath: String,
)
