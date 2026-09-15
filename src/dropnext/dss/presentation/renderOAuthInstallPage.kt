package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.WebhookRegistrationReport
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
  val summary = "Webhook subscriptions: ${report.activeCount} already active, ${report.addedCount} added in this install, " +
    "${report.failures.size} failed, ${report.staleCount} pointing elsewhere."
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
    is WebhookTopicStatus.Missing -> span { style = "color:#b45309"; +"missing" }
    is WebhookTopicStatus.Failed -> span { style = "color:red"; strong { +"failed" } }
  }
}

private fun FlowContent.renderSubscription(status: WebhookTopicStatus) {
  val subscription = when (status) {
    is WebhookTopicStatus.Active -> status.subscription
    is WebhookTopicStatus.Added -> status.subscription
    is WebhookTopicStatus.Missing, is WebhookTopicStatus.Failed -> null
  }
  if (subscription == null) {
    +"-"
    return
  }
  code { +subscription.uri }
  +" (id "
  code { +subscription.id }
  +")"
}

private fun FlowContent.renderFailedTopics(failures: List<WebhookTopicRegistration>) {
  p {
    style = "color:red"
    strong { +"Webhook registrations that failed (check app scopes in Partner Dashboard):" }
  }
  ul {
    failures.forEach { row ->
      li {
        style = "color:red"
        code { +row.topic }
        +" — "
        +(row.status as WebhookTopicStatus.Failed).error
      }
    }
  }
  p {
    +"Make sure your Shopify Partner Dashboard app has Orders API access enabled and grants the scopes the install "
    +"requests. Reinstall the app after fixing."
  }
}
