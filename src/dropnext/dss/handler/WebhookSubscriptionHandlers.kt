package dropnext.dss.handler

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.respondError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlServiceFactory
import dropnext.dss.lib.slf4j.SHOP_MDC_KEY
import dropnext.dss.lib.slf4j.withMdcEntries
import dropnext.dss.path.Paths
import dropnext.dss.workflow.reregisterShopifyWebhooks
import dropnext.dss.workflow.scanShopifyWebhooks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable


private val log = KotlinLogging.logger {}

/**
 * The per-shop webhook subscription endpoints the monolith calls, both behind the bearer auth
 * ([dropnext.dss.routing.webhookSubscriptionRoutes]): the check reads what Shopify has for a shop, the registration
 * brings the shop to what this version handles. Both answer the same rows.
 */
class WebhookSubscriptionHandlers(
  dssBaseUrl: String,
  private val shopifyGraphqlServiceFactory: ShopifyGraphqlServiceFactory,
) {
  private val webhookCallbackUrl = "$dssBaseUrl${Paths.webhooksShopify}"

  /**
   * Readiness for one shop: the token resolves and, per handled topic, what Shopify is subscribed
   * to at our callback URL. The scan is read-only, so the question "is this shop still subscribed"
   * (Shopify removes a subscription whose deliveries keep failing) can be asked without a reinstall.
   * Behind the bearer auth: it drives a monolith lookup and a Shopify query per call.
   */
  suspend fun handleApiCheck(call: ApplicationCall) {
    val rawShop = call.request.queryParameters.getOrFail("shop")
    val shop = call.shopDomainOrRespond(rawShop, "shop") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      // The factory goes through the token store, monolith lookup included, so the check answers what a webhook would find.
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries

      val webhooks = when (val scanned = scanShopifyWebhooks(shopify, webhookCallbackUrl)) {
        is Success -> scanned.value
        is Failure -> {
          log.warn { "api check: webhook subscriptions query failed error=${scanned.reason.message}" }
          return@withMdcEntries call.respondError(SUBSCRIPTIONS_UNLISTABLE)
        }
      }

      call.respond(
        ApiCheckResponse(
          shop = shop.normalizedShopifyHost,
          checks = ApiCheckDetails(hasTokenMappedForShop = true),
          webhooks = webhooks.topicRows(),
          obsoleteWebhooks = webhooks.obsoleteRows(),
        ),
      )
    }
  }

  /**
   * Registers one shop's webhook subscriptions the way an install does, without the merchant: how a change to the
   * handled topics or their payload fields reaches a shop installed before it. The monolith calls it once per shop it
   * wants to bring along. A `200` carries the rows the check answers, with what this run changed; a topic Shopify refused
   * is a row, not the status, because what the run did for the other topics stands. Running it again changes nothing
   * that is already right. The token is not touched: without the merchant there is no new one to exchange.
   */
  suspend fun handleRegisterWebhooks(call: ApplicationCall) {
    val rawShop = call.request.queryParameters.getOrFail("shop")
    val shop = call.shopDomainOrRespond(rawShop, "shop") ?: return
    withMdcEntries(SHOP_MDC_KEY to shop.normalizedShopifyHost) {
      val shopify = call.shopifyServiceOrRespond(shopifyGraphqlServiceFactory, shop) ?: return@withMdcEntries

      val report = when (val registered = reregisterShopifyWebhooks(shopify, webhookCallbackUrl)) {
        is Success -> registered.value
        is Failure -> {
          log.warn { "webhook registration: webhook subscriptions query failed error=${registered.reason.message}" }
          return@withMdcEntries call.respondError(SUBSCRIPTIONS_UNLISTABLE)
        }
      }

      call.respond(
        RegisterWebhooksResponse(
          shop = shop.normalizedShopifyHost,
          summary = WebhookRegistrationSummary(
            active = report.activeCount,
            added = report.addedCount,
            updated = report.updatedCount,
            repointed = report.repointedCount,
            failed = report.failures.size,
            stale = report.staleCount,
            deleted = report.deletedCount,
            obsolete = report.obsoleteCount,
          ),
          webhooks = report.topicRows(),
          obsoleteWebhooks = report.obsoleteRows(),
        ),
      )
    }
  }
}


private val SUBSCRIPTIONS_UNLISTABLE = DssError.UpstreamFailure("could not list the shop's webhook subscriptions")

@Serializable
private data class ApiCheckResponse(
  val shop: String,
  val checks: ApiCheckDetails,
  val webhooks: List<WebhookTopicRow>,
  val obsoleteWebhooks: List<ObsoleteWebhookRow>,
)

@Serializable
private data class ApiCheckDetails(
  val hasTokenMappedForShop: Boolean,
)

@Serializable
private data class RegisterWebhooksResponse(
  val shop: String,
  val summary: WebhookRegistrationSummary,
  val webhooks: List<WebhookTopicRow>,
  val obsoleteWebhooks: List<ObsoleteWebhookRow>,
)

/** The counts the registration's log line carries, so a caller running it for many shops need not walk the rows. */
@Serializable
private data class WebhookRegistrationSummary(
  val active: Int,
  val added: Int,
  val updated: Int,
  val repointed: Int,
  val failed: Int,
  val stale: Int,
  val deleted: Int,
  val obsolete: Int,
)

/**
 * One handled topic. A scan answers `active`, `mismatched` or `missing` at our callback URL; a registration also `added`,
 * `updated`, `repointed`, `not_applied` and `failed`. A subscription that does not deliver as the topic declares (a
 * mismatched one, or what Shopify kept of an update it did not apply) is named with both field lists; `[]` is the full
 * payload in both. Shopify's own error text stays in the log.
 */
@Serializable
private data class WebhookTopicRow(
  val topic: String,
  val status: String,
  val id: String? = null,
  val uri: String? = null,
  val previousUri: String? = null,
  val includeFields: List<String>? = null,
  val expectedIncludeFields: List<String>? = null,
  val filter: String? = null,
  val format: String? = null,
  val stale: List<StaleSubscriptionRow> = emptyList(),
)

@Serializable
private data class StaleSubscriptionRow(
  val id: String,
  val uri: String,
)

/** A subscription at our callback URL for a topic the service does not handle: `obsolete` while it is there, `deleted`, or `delete_failed`. */
@Serializable
private data class ObsoleteWebhookRow(
  val topic: String,
  val id: String,
  val status: String,
)

private fun WebhookRegistrationReport.topicRows(): List<WebhookTopicRow> =
  topics.map { row ->
    val stale = row.stale.map { StaleSubscriptionRow(id = it.id, uri = it.uri) }
    when (val status = row.status) {
      is WebhookTopicStatus.Active -> addressRow(row.topic, "active", status.subscription, stale)
      is WebhookTopicStatus.Added -> addressRow(row.topic, "added", status.subscription, stale)
      is WebhookTopicStatus.Updated -> addressRow(row.topic, "updated", status.subscription, stale)
      is WebhookTopicStatus.Repointed ->
        addressRow(row.topic, "repointed", status.subscription, stale).copy(previousUri = status.previousUri)
      is WebhookTopicStatus.Mismatched ->
        deliveryComparisonRow(row.topic, "mismatched", status.subscription, status.expectedIncludeFields, stale)
      is WebhookTopicStatus.NotApplied ->
        deliveryComparisonRow(row.topic, "not_applied", status.answered, status.expectedIncludeFields, stale)
      is WebhookTopicStatus.Missing -> WebhookTopicRow(row.topic, "missing", stale = stale)
      is WebhookTopicStatus.Failed -> WebhookTopicRow(row.topic, "failed", stale = stale)
    }
  }

private fun addressRow(topic: String, status: String, subscription: WebhookSubscriptionStatus, stale: List<StaleSubscriptionRow>) =
  WebhookTopicRow(topic, status, id = subscription.id, uri = subscription.uri, stale = stale)

private fun deliveryComparisonRow(
  topic: String,
  status: String,
  subscription: WebhookSubscriptionStatus,
  expectedIncludeFields: List<String>,
  stale: List<StaleSubscriptionRow>,
) = WebhookTopicRow(
  topic = topic,
  status = status,
  id = subscription.id,
  uri = subscription.uri,
  includeFields = subscription.includeFields,
  expectedIncludeFields = expectedIncludeFields,
  filter = subscription.filter,
  format = subscription.format,
  stale = stale,
)

private fun WebhookRegistrationReport.obsoleteRows(): List<ObsoleteWebhookRow> =
  obsolete.map { row ->
    val status = when (row.removal) {
      is ObsoleteSubscriptionRemoval.NotAttempted -> "obsolete"
      is ObsoleteSubscriptionRemoval.Deleted -> "deleted"
      is ObsoleteSubscriptionRemoval.Failed -> "delete_failed"
    }
    ObsoleteWebhookRow(topic = row.subscription.topic, id = row.subscription.id, status = status)
  }
