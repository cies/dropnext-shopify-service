package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.map
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import io.github.oshai.kotlinlogging.KotlinLogging


private val log = KotlinLogging.logger {}

/** The handled topics that have a subscription topic, in registration order. */
private val registrableTopics: List<ShopifyWebhookTopic> = ShopifyWebhookTopic.known.filter { it.subscriptionTopic != null }

/**
 * Read-only: what Shopify has for every handled topic, sorted into subscribed at [callbackUrl],
 * [WebhookTopicStatus.Missing], and pointing elsewhere. One query without a URL filter, so a
 * subscription left behind at an earlier callback URL shows up as stale instead of staying invisible.
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
        WebhookTopicRegistration(
          topic = name,
          status = ours.firstOrNull()?.let(WebhookTopicStatus::Active) ?: WebhookTopicStatus.Missing,
          stale = elsewhere,
        )
      },
    )
  }

/**
 * Subscribes the shop to every handled topic it is not yet subscribed to at [callbackUrl], each with
 * the payload fields the topic declares (the `orders` topics are id-only, because the DSS fetches the
 * full order via Graphql after the webhook arrives). A topic already subscribed there is left alone and
 * reported as active: registering it again is what made every reinstall show five failures.
 *
 * Composes [scanShopifyWebhooks] and [ShopifyGraphqlService.registerWebhook].
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
      if (row.status !is WebhookTopicStatus.Missing) return@map row
      val topic = registrableTopics.first { it.subscriptionTopic!!.name == row.topic }
      val registered = shopify.registerWebhook(topic.subscriptionTopic!!, callbackUrl, topic.includeFields)
      row.copy(
        status = when (registered) {
          is Success -> WebhookTopicStatus.Added(WebhookSubscriptionStatus(id = registered.value, topic = row.topic, uri = callbackUrl))
          is Failure -> WebhookTopicStatus.Failed(registered.reason.message)
        },
      )
    },
  )

  report.failures.forEach { row ->
    log.warn { "Webhook registration failed topic=${row.topic} error=${(row.status as WebhookTopicStatus.Failed).error}" }
  }
  report.topics.forEach { row ->
    row.stale.forEach { log.warn { "Webhook subscription stale topic=${row.topic} uri=${it.uri} id=${it.id}" } }
  }
  log.info {
    "Webhooks registered active=${report.activeCount} added=${report.addedCount} " +
      "failed=${report.failures.size} stale=${report.staleCount}"
  }
  return report
}
