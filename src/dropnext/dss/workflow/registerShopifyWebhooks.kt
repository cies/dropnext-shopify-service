package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.map
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.graphql.generated.enums.WebhookSubscriptionFormat
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/** The handled topics that have a subscription topic, in registration order. */
private val registrableTopics: List<ShopifyWebhookTopic> = ShopifyWebhookTopic.known.filter { it.subscriptionTopic != null }

/**
 * Read-only: what Shopify has for every handled topic, sorted into subscribed at [callbackUrl] delivering as the topic
 * declares, [WebhookTopicStatus.Mismatched] (subscribed there delivering otherwise), [WebhookTopicStatus.Missing],
 * and pointing elsewhere. One query without a URL filter, so a subscription left behind at an earlier callback URL shows
 * up as stale instead of staying invisible.
 * Composes [ShopifyGraphqlService.webhookSubscriptions].
 */
suspend fun scanShopifyWebhooks(
  shopify: ShopifyGraphqlService,
  callbackUrl: String,
): ShopifyResult<WebhookRegistrationReport> =
  shopify.webhookSubscriptions(registrableTopics.mapNotNull { it.subscriptionTopic }, callbackUrl = null).map { all ->
    WebhookRegistrationReport(
      registrableTopics.map { topic ->
        val name = topic.subscriptionTopic!!.name
        val ours = all.filter { it.topic == name && it.uri == callbackUrl }
        val elsewhere = all.filter { it.topic == name && it.uri != callbackUrl }
        WebhookTopicRegistration(topic = name, status = scannedStatus(topic, ours), stale = elsewhere)
      },
    )
  }

/** A subscription delivering as the topic declares wins wherever Shopify lists it; only without one is the first at our URL mismatched. */
private fun scannedStatus(topic: ShopifyWebhookTopic, ours: List<WebhookSubscriptionStatus>): WebhookTopicStatus {
  val matching = ours.firstOrNull { topic.matchesSubscription(it) }
  if (matching != null) return WebhookTopicStatus.Active(matching)
  val mismatched = ours.firstOrNull() ?: return WebhookTopicStatus.Missing
  return WebhookTopicStatus.Mismatched(mismatched, expectedIncludeFields = topic.includeFields.orEmpty())
}

/**
 * Leaves the shop subscribed at [callbackUrl] to every handled topic, each with the payload fields the topic declares
 * (the `orders` topics are id-only, because the DSS fetches the full order via Graphql after the webhook arrives), no
 * filter and JSON.
 *
 * - A topic already subscribed there that way is left alone: registering it again is what made every reinstall show
 *   five failures.
 * - One subscribed there delivering otherwise is updated in place, since Shopify refuses a second subscription for the
 *   same topic and address.
 * - A missing topic first repoints a stale HTTPS subscription to [callbackUrl], so the old address stops receiving
 *   copies, and is registered only when there is none or the repoint did not work. One per topic, for the same reason;
 *   nothing is deleted.
 *
 * Composes [scanShopifyWebhooks], [ShopifyGraphqlService.updateWebhookSubscription] and [ShopifyGraphqlService.registerWebhook].
 */
suspend fun registerShopifyWebhooks(
  shopify: ShopifyGraphqlService,
  callbackUrl: String,
): WebhookRegistrationReport {
  val scanned = when (val scan = scanShopifyWebhooks(shopify, callbackUrl)) {
    is Success -> scan.value
    is Failure -> {
      // Without the scan every topic is registered; Shopify refuses the ones that exist, which the report then shows.
      log.warn { "Webhook subscriptions query failed error=${scan.reason.message}, registering every topic" }
      WebhookRegistrationReport(registrableTopics.map { WebhookTopicRegistration(it.subscriptionTopic!!.name, WebhookTopicStatus.Missing) })
    }
  }

  val report = WebhookRegistrationReport(
    scanned.topics.map { row ->
      val topic = registrableTopics.first { it.subscriptionTopic!!.name == row.topic }
      when (val status = row.status) {
        is WebhookTopicStatus.Missing -> repointOrRegister(shopify, topic, row, callbackUrl)
        is WebhookTopicStatus.Mismatched -> row.copy(
          status = when (val updated = updateSubscription(shopify, topic, status.subscription.id, callbackUrl)) {
            is Success -> WebhookTopicStatus.Updated(updated.value)
            is Failure -> updated.reason
          },
        )
        is WebhookTopicStatus.Active, is WebhookTopicStatus.Added, is WebhookTopicStatus.Updated,
        is WebhookTopicStatus.Repointed, is WebhookTopicStatus.Unsuccessful -> row
      }
    },
  )

  report.failures.forEach { row ->
    log.warn { "Webhook registration failed topic=${row.topic} ${failureLogDetail(row.status as WebhookTopicStatus.Unsuccessful)}" }
  }
  report.topics.forEach { row ->
    row.stale.forEach { log.warn { "Webhook subscription stale topic=${row.topic} uri=${it.uri} id=${it.id}" } }
  }
  log.info {
    "Webhooks registered active=${report.activeCount} added=${report.addedCount} updated=${report.updatedCount} " +
      "repointed=${report.repointedCount} failed=${report.failures.size} stale=${report.staleCount}"
  }
  return report
}

/**
 * Only an HTTPS subscription is a candidate: a Pub/Sub or EventBridge one for the same topic is a pipeline someone set
 * up on purpose, not an earlier address of this service.
 */
private suspend fun repointOrRegister(
  shopify: ShopifyGraphqlService,
  topic: ShopifyWebhookTopic,
  row: WebhookTopicRegistration,
  callbackUrl: String,
): WebhookTopicRegistration {
  val candidate = row.stale.firstOrNull { it.uri.startsWith("https://") }
    ?: return row.copy(status = registerSubscription(shopify, topic, callbackUrl))

  return when (val repointed = updateSubscription(shopify, topic, candidate.id, callbackUrl)) {
    is Success -> {
      log.info { "Webhook subscription repointed topic=${row.topic} id=${candidate.id} previousUri=${candidate.uri}" }
      row.copy(status = WebhookTopicStatus.Repointed(repointed.value, previousUri = candidate.uri), stale = row.stale - candidate)
    }
    is Failure -> {
      log.warn {
        "Webhook subscription repoint failed topic=${row.topic} id=${candidate.id} previousUri=${candidate.uri} " +
          failureLogDetail(repointed.reason)
      }
      row.copy(status = registerSubscription(shopify, topic, callbackUrl))
    }
  }
}

/**
 * Shopify's answer is checked rather than trusted: an update it accepted but did not apply, such as a `null` read as
 * "keep the fields", must not read as a repair.
 */
private suspend fun updateSubscription(
  shopify: ShopifyGraphqlService,
  topic: ShopifyWebhookTopic,
  subscriptionId: String,
  callbackUrl: String,
): Result<WebhookSubscriptionStatus, WebhookTopicStatus.Unsuccessful> {
  val answered = when (val answer = shopify.updateWebhookSubscription(subscriptionId, callbackUrl, topic.includeFields)) {
    is Success -> answer.value
    is Failure -> return Failure(WebhookTopicStatus.Failed(answer.reason.message))
  }
  if (answered.uri != callbackUrl || !topic.matchesSubscription(answered)) {
    return Failure(WebhookTopicStatus.NotApplied(answered, expectedIncludeFields = topic.includeFields.orEmpty()))
  }
  return Success(answered)
}

private suspend fun registerSubscription(
  shopify: ShopifyGraphqlService,
  topic: ShopifyWebhookTopic,
  callbackUrl: String,
): WebhookTopicStatus {
  val subscriptionTopic = topic.subscriptionTopic!!
  return when (val registered = shopify.registerWebhook(subscriptionTopic, callbackUrl, topic.includeFields)) {
    is Success -> WebhookTopicStatus.Added(
      WebhookSubscriptionStatus(
        id = registered.value,
        topic = subscriptionTopic.name,
        uri = callbackUrl,
        includeFields = topic.includeFields.orEmpty(),
        filter = null,
        format = WebhookSubscriptionFormat.JSON.name,
      ),
    )
    is Failure -> WebhookTopicStatus.Failed(registered.reason.message)
  }
}

/**
 * The `key=value` detail a log line carries for an unsuccessful topic; `[]` is the full payload. A filter's text stays
 * out: it is search syntax with spaces in it, typed by whoever made the subscription.
 */
private fun failureLogDetail(status: WebhookTopicStatus.Unsuccessful): String =
  when (status) {
    is WebhookTopicStatus.Failed -> "error=${status.error}"
    is WebhookTopicStatus.NotApplied -> with(status.answered) {
      "error=update_not_applied uri=$uri includeFields=${includeFields.joinToString(",", "[", "]")} " +
        "hasFilter=$hasFilter format=$format"
    }
  }
