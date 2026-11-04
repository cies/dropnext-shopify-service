package dropnext.dss.mapper

import dropnext.dss.contract.ObsoleteShopifyWebhookRow
import dropnext.dss.contract.ShopifyAccessScopesStatus
import dropnext.dss.contract.ShopifyWebhookRegistrationSummary
import dropnext.dss.contract.ShopifyWebhookTopicRow
import dropnext.dss.contract.StaleShopifyWebhookRow
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.ShopifyAccessScopeReport
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicStatus


/** The two lists the store status and the webhook registration both answer. */
data class ShopifyStoreStatusRows(
  val webhooks: List<ShopifyWebhookTopicRow>,
  val obsoleteWebhooks: List<ObsoleteShopifyWebhookRow>,
)

/**
 * One row per handled topic and one per obsolete subscription. A scan answers `active`, `mismatched` or `missing`; a
 * registration also `added`, `updated`, `repointed`, `not_applied` and `failed`. A subscription that does not deliver
 * as the topic declares (a mismatched one, or what Shopify kept of an update it did not apply) is named with both
 * field lists, where `[]` is the full payload. Shopify's own error text stays in the log.
 */
fun WebhookRegistrationReport.toShopifyStoreStatusRows(): ShopifyStoreStatusRows =
  ShopifyStoreStatusRows(webhooks = topicRows(), obsoleteWebhooks = obsoleteRows())

/**
 * The grant against the scopes the install asks for. A status rather than a missing object, so a grant Shopify could
 * not list ([UNKNOWN_SHOPIFY_ACCESS_SCOPES]) reads differently from a version of the service that does not report one.
 */
fun ShopifyAccessScopeReport.toShopifyAccessScopesStatus(): ShopifyAccessScopesStatus =
  ShopifyAccessScopesStatus(
    status = if (isComplete) "complete" else "missing",
    granted = grantedHandles,
    missing = missing.map { it.handle },
  )

val UNKNOWN_SHOPIFY_ACCESS_SCOPES = ShopifyAccessScopesStatus(status = "unknown", granted = emptyList(), missing = emptyList())

/** The counts the registration's log line carries, so a caller running it for many shops need not walk the rows. */
fun WebhookRegistrationReport.toShopifyWebhookRegistrationSummary(): ShopifyWebhookRegistrationSummary =
  ShopifyWebhookRegistrationSummary(
    active = activeCount,
    added = addedCount,
    updated = updatedCount,
    repointed = repointedCount,
    failed = failures.size,
    stale = staleCount,
    deleted = deletedCount,
    obsolete = obsoleteCount,
  )

private fun WebhookRegistrationReport.topicRows(): List<ShopifyWebhookTopicRow> =
  topics.map { row ->
    // Left out when empty, as the rows have always been answered; the contract reads an absent list as `[]`.
    val stale = row.stale.map { StaleShopifyWebhookRow(id = it.id, uri = it.uri) }.takeIf { it.isNotEmpty() }
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
      is WebhookTopicStatus.Missing -> ShopifyWebhookTopicRow(row.topic, "missing", stale = stale)
      is WebhookTopicStatus.Failed -> ShopifyWebhookTopicRow(row.topic, "failed", stale = stale)
    }
  }

private fun addressRow(
  topic: String,
  status: String,
  subscription: WebhookSubscriptionStatus,
  stale: List<StaleShopifyWebhookRow>?,
) = ShopifyWebhookTopicRow(topic, status, id = subscription.id, uri = subscription.uri, stale = stale)

private fun deliveryComparisonRow(
  topic: String,
  status: String,
  subscription: WebhookSubscriptionStatus,
  expectedIncludeFields: List<String>,
  stale: List<StaleShopifyWebhookRow>?,
) = ShopifyWebhookTopicRow(
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

/** `obsolete` while it is there, `deleted`, or `delete_failed`. */
private fun WebhookRegistrationReport.obsoleteRows(): List<ObsoleteShopifyWebhookRow> =
  obsolete.map { row ->
    val status = when (row.removal) {
      is ObsoleteSubscriptionRemoval.NotAttempted -> "obsolete"
      is ObsoleteSubscriptionRemoval.Deleted -> "deleted"
      is ObsoleteSubscriptionRemoval.Failed -> "delete_failed"
    }
    ObsoleteShopifyWebhookRow(topic = row.subscription.topic, id = row.subscription.id, status = status)
  }
