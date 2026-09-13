package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.config.Config
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.path.Paths
import dropnext.dss.workflow.scanShopifyWebhooks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable


private val log = KotlinLogging.logger {}

/** Handlers for the diagnostic/health endpoints (`/`, `/health`, `/api`, `/api/check`, …). */
class DiagnosticsHandlers(
  private val dssConfig: Config,
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
) {
  suspend fun handleIndex(call: ApplicationCall) {
    call.respondText(
      """
      API is running.

      --- Infrastructure / diagnostics ---
        GET  ${Paths.index.padEnd(26)}This index
        GET  ${Paths.health.padEnd(26)}Liveness probe - JSON with status "ok" and the running version
        GET  ${Paths.api.padEnd(26)}JSON diagnostic info (config, URLs, issues)
        GET  ${Paths.apiCheck.padEnd(20)}?shop=  Readiness for a specific shop: token resolvable, webhook subscriptions (Bearer <MONOLITH_TO_DSS_API_KEY> required)
        GET  ${Paths.apiRedirectUrl.padEnd(26)}Full OAuth redirect URL

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
   * Readiness for one shop: the token resolves and, per handled topic, what Shopify is subscribed
   * to at our callback URL. The scan is read-only, so the question "is this shop still subscribed"
   * (Shopify removes a subscription whose deliveries keep failing) can be asked without a reinstall.
   * Behind the bearer auth: it drives a monolith lookup and a Shopify query per call.
   */
  suspend fun handleApiCheck(call: ApplicationCall) {
    val rawShop = call.request.queryParameters.getOrFail("shop")
    val shop = call.shopDomainOrRespond(rawShop, "shop") ?: return

    // The factory goes through the token store, monolith lookup included, so the check answers what a webhook would find.
    val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return

    val webhooks = when (val scanned = scanShopifyWebhooks(shopify, "${dssConfig.dssBaseUrl}${Paths.webhooksShopify}")) {
      is Success -> scanned.value
      is Failure -> {
        log.warn { "api check: webhook subscriptions query failed shop=${shop.normalizedShopifyHost} error=${scanned.reason.message}" }
        return call.respondError(DssError.UpstreamFailure("could not list the shop's webhook subscriptions"))
      }
    }

    call.respond(
      ApiCheckResponse(
        shop = shop.normalizedShopifyHost,
        checks = ApiCheckDetails(hasTokenMappedForShop = true),
        webhooks = webhooks.toApiCheckWebhooks(),
      ),
    )
  }

  /** The same shape as the monolith's `/health`, so one probe reads both services. */
  suspend fun handleHealth(call: ApplicationCall) {
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
  val dssBaseUrl: String,
  val oauthRedirectPath: String,
)

@Serializable
private data class ApiCheckResponse(
  val shop: String,
  val checks: ApiCheckDetails,
  val webhooks: List<ApiCheckWebhook>,
)

@Serializable
private data class ApiCheckDetails(
  val hasTokenMappedForShop: Boolean,
)

/** One handled topic: `active` or `missing` at our callback URL (a scan never registers), plus what points elsewhere. */
@Serializable
private data class ApiCheckWebhook(
  val topic: String,
  val status: String,
  val id: String? = null,
  val uri: String? = null,
  val stale: List<ApiCheckStaleSubscription> = emptyList(),
)

@Serializable
private data class ApiCheckStaleSubscription(
  val id: String,
  val uri: String,
)

private fun WebhookRegistrationReport.toApiCheckWebhooks(): List<ApiCheckWebhook> =
  topics.map { row ->
    val subscription = (row.status as? WebhookTopicStatus.Active)?.subscription
    ApiCheckWebhook(
      topic = row.topic,
      status = if (subscription != null) "active" else "missing",
      id = subscription?.id,
      uri = subscription?.uri,
      stale = row.stale.map { ApiCheckStaleSubscription(id = it.id, uri = it.uri) },
    )
  }
