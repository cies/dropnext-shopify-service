package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.ObsoleteWebhookSubscription
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import kotlinx.html.*
import kotlinx.html.stream.appendHTML


/** Renders the post-install confirmation page as a complete HTML document. */
fun renderOAuthInstallPage(report: ShopInstallReport): String = StringBuilder("<!DOCTYPE html>\n").appendHTML().html {
  head {
    meta { name = "robots"; content = "noindex, nofollow" }
  }
  body {
    h1 { +"App installed" }
    p { +"Shop: ${report.shop.normalizedShopifyHost} (id ${report.shopId?.value ?: "unknown"})" }
    renderMonolithPersistBlock(report.monolithPersist)
    p { +"Products in the shop: ${report.productCount?.let(::describeProductCount) ?: "unknown (lookup failed)"}" }
    p {
      +"Webhook callback URL: "
      code { +report.webhookCallbackUrl }
    }
    renderWebhookSummary(report.webhooks)
    renderWebhookTable(report.webhooks.topics)
    if (report.webhooks.failures.isNotEmpty()) {
      renderFailedTopics(report.webhooks.failures)
    }
    if (report.webhooks.obsolete.isNotEmpty()) {
      renderObsoleteSubscriptions(report.webhooks.obsolete)
    }
  }
}.toString()

/** Shopify stops counting at a cap, past which its count is a lower bound. */
private fun describeProductCount(productCount: ProductCount): String =
  if (productCount.isExact) "${productCount.count}" else "at least ${productCount.count}"

private fun FlowContent.renderMonolithPersistBlock(outcome: MonolithPersistOutcome) {
  when (outcome) {
    is MonolithPersistOutcome.Persisted -> p {
      style = "color:green"
      strong { +"Shopify token saved via monolith" }
      +" (store_id=${outcome.storeId}) and cached in memory - webhooks and routes can use this process immediately."
    }

    is MonolithPersistOutcome.Failed -> p {
      style = "color:#b91c1c"
      strong { +"Saving the token to the monolith failed" }
      +" (${outcome.httpStatus?.let { "HTTP status $it" } ?: "no response"}). Token is cached in this server's memory only."
      if (!outcome.detail.isNullOrBlank()) {
        +" Details: "
        code { +outcome.detail.take(400) }
      }
    }
  }
}

private fun FlowContent.renderWebhookSummary(report: WebhookRegistrationReport) {
  val summary = "Webhook subscriptions: ${report.activeCount} already active, ${report.addedCount} added, " +
    "${report.updatedCount} updated and ${report.repointedCount} repointed from another URL in this install, " +
    "${report.failures.size} failed, ${report.staleCount} pointing elsewhere, " +
    "${report.deletedCount} deleted for a topic the service no longer handles."
  p { +summary }
}

/** One row per handled topic: what the shop is subscribed to, what this run changed, and what points at an old URL. */
private fun FlowContent.renderWebhookTable(rows: List<WebhookTopicRegistration>) {
  table {
    thead {
      tr {
        th { +"Topic" }
        th { +"Status" }
        th { +"Subscription" }
        th { +"Elsewhere" }
      }
    }
    tbody {
      rows.forEach { row ->
        tr {
          td { code { +row.topic } }
          td { renderStatus(row.status) }
          td { renderSubscription(row.status) }
          td {
            if (row.stale.isEmpty()) +"-"
            row.stale.forEach { stale ->
              div {
                style = "color:#b45309"
                code { +stale.uri }
                +" (id "
                code { +stale.id }
                +")"
              }
            }
          }
        }
      }
    }
  }
}

private fun FlowContent.renderStatus(status: WebhookTopicStatus) {
  when (status) {
    is WebhookTopicStatus.Active -> span { style = "color:green"; +"active" }
    is WebhookTopicStatus.Added -> span { style = "color:green"; strong { +"added" } }
    is WebhookTopicStatus.Updated -> span { style = "color:green"; strong { +"updated" } }
    is WebhookTopicStatus.Repointed -> span { style = "color:green"; strong { +"repointed" } }
    is WebhookTopicStatus.Mismatched -> span { style = "color:#b45309"; +"mismatched" }
    is WebhookTopicStatus.Missing -> span { style = "color:#b45309"; +"missing" }
    is WebhookTopicStatus.NotApplied -> span { style = "color:red"; strong { +"not applied" } }
    is WebhookTopicStatus.Failed -> span { style = "color:red"; strong { +"failed" } }
  }
}

private fun FlowContent.renderSubscription(status: WebhookTopicStatus) {
  when (status) {
    is WebhookTopicStatus.Active -> renderSubscriptionAddress(status.subscription)
    is WebhookTopicStatus.Added -> renderSubscriptionAddress(status.subscription)
    is WebhookTopicStatus.Updated -> renderSubscriptionAddress(status.subscription)
    is WebhookTopicStatus.Repointed -> {
      renderSubscriptionAddress(status.subscription)
      +" (was "
      code { +status.previousUri }
      +")"
    }
    is WebhookTopicStatus.Mismatched -> renderDeliveryComparison(status.subscription, status.expectedIncludeFields)
    is WebhookTopicStatus.NotApplied -> renderDeliveryComparison(status.answered, status.expectedIncludeFields)
    is WebhookTopicStatus.Missing, is WebhookTopicStatus.Failed -> +"-"
  }
}

private fun FlowContent.renderSubscriptionAddress(subscription: WebhookSubscriptionStatus) {
  code { +subscription.uri }
  +" (id "
  code { +subscription.id }
  +")"
}

private fun FlowContent.renderDeliveryComparison(subscription: WebhookSubscriptionStatus, expectedIncludeFields: List<String>) {
  renderSubscriptionAddress(subscription)
  +", fields: "
  code { +describeIncludeFields(subscription.includeFields) }
  +", expected: "
  code { +describeIncludeFields(expectedIncludeFields) }
  // The service sets neither, so only one someone else set is worth naming.
  if (subscription.hasFilter) {
    +", filter: "
    code { +subscription.filter.orEmpty() }
  }
  if (!subscription.isJson) {
    +", format: "
    code { +subscription.format }
  }
}

/** Shopify reports the full payload as an empty list, which would otherwise read as no fields at all. */
private fun describeIncludeFields(fields: List<String>): String = if (fields.isEmpty()) "all fields" else fields.joinToString(", ")

/** What is subscribed at our callback URL for a topic the service does not handle, and whether this run got rid of it. */
private fun FlowContent.renderObsoleteSubscriptions(obsolete: List<ObsoleteWebhookSubscription>) {
  p { +"Subscriptions at the webhook callback URL for a topic the service no longer handles:" }
  ul {
    obsolete.forEach { row ->
      li {
        code { +row.subscription.topic }
        +" (id "
        code { +row.subscription.id }
        +"): "
        when (val removal = row.removal) {
          is ObsoleteSubscriptionRemoval.Deleted -> span { style = "color:green"; +"deleted" }
          is ObsoleteSubscriptionRemoval.NotAttempted -> span { style = "color:#b45309"; +"still subscribed" }
          is ObsoleteSubscriptionRemoval.Failed -> span {
            style = "color:red"
            strong { +"deletion failed: " }
            +removal.error
          }
        }
      }
    }
  }
}

private fun FlowContent.renderFailedTopics(failures: List<WebhookTopicRegistration>) {
  p {
    style = "color:red"
    strong { +"Webhook subscriptions that could not be registered or updated:" }
  }
  ul {
    failures.forEach { row ->
      li {
        style = "color:red"
        code { +row.topic }
        +" — "
        when (val status = row.status as WebhookTopicStatus.Unsuccessful) {
          is WebhookTopicStatus.Failed -> +status.error
          is WebhookTopicStatus.NotApplied -> +"Shopify accepted the update but did not apply it."
        }
      }
    }
  }
  p {
    +"Make sure your Shopify Partner Dashboard app has Orders API access enabled and grants the scopes the install "
    +"requests. Reinstall the app after fixing."
  }
}
