package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.ObsoleteWebhookSubscription
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopInstallOutcome
import dropnext.dss.domain.ShopInstallReport
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAccessScopeReport
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.domain.outcome
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import java.util.Base64
import java.util.Locale


private const val SUPPORT_ADDRESS = "support@dropnext.com"
private const val LOGO_RESOURCE = "dropnext-logo.svg"
private const val INTER_STYLESHEET = "https://fonts.googleapis.com/css2?family=Inter:wght@400..700&display=swap"

/**
 * The monolith's e-mail frame and palette (`EmailStyle`), with the portal's status colors and font.
 * Written raw, as a style element does not decode the entities the builder escapes text into.
 * A fixed string, never a value: the one thing this view writes unescaped.
 */
private val STYLE_SHEET = """
  html, body { background-color: #f4f4f5; }
  body {
    margin: 0; padding: 32px 16px; color: #1a1a1a;
    font-family: Inter, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
    font-size: 16px; line-height: 24px; -webkit-text-size-adjust: 100%;
  }
  .card { box-sizing: border-box; max-width: 600px; margin: 0 auto; padding: 32px; background-color: #ffffff; }
  .logo { display: block; width: 160px; height: auto; margin: 0 0 24px 0; }
  h1 { margin: 0 0 16px 0; font-size: 22px; line-height: 30px; font-weight: 600; color: #333333; }
  p, ul { margin: 0 0 16px 0; }
  a { color: #755ff8; }
  .notice { margin: 0 0 16px 0; padding: 12px 16px; border-radius: 6px; }
  .notice p:last-child, .notice ul:last-child { margin-bottom: 0; }
  .notice ul { padding-left: 20px; }
  .notice-warn { color: #816316; background-color: #fff2c9; }
  .notice-bad { color: #c52727; background-color: #ffe8e8; }
  .notice a { color: inherit; }
  .actions { margin: 8px 0 24px 0; }
  .button {
    display: inline-block; box-sizing: border-box; max-width: 100%; padding: 12px 24px; border-radius: 6px;
    background-color: #755ff8; color: #ffffff; font-weight: 600; line-height: 20px; text-decoration: none;
  }
  details, .reference { margin: 0 0 16px 0; color: #666666; font-size: 13px; line-height: 20px; }
  summary { cursor: pointer; }
  details p, details ul { margin: 8px 0; }
  .table-scroll { max-width: 100%; overflow-x: auto; }
  details table { border-collapse: collapse; }
  details th, details td { padding: 4px 8px; border-bottom: 1px solid #e0e0e0; text-align: left; vertical-align: top; }
  details p code, .reference code { overflow-wrap: anywhere; word-break: break-all; }
  .status-ok { color: #15773a; }
  .status-warn { color: #816316; }
  .status-bad { color: #c52727; }
  .footer {
    margin: 24px 0 0 0; padding: 16px 0 0 0; border-top: 1px solid #e0e0e0; text-align: center;
    color: #999999; font-size: 13px; line-height: 20px;
  }
  .footer p { margin: 0; }
  @media (max-width: 480px) {
    body { padding: 0; }
    .card { padding: 24px; }
  }
""".trimIndent()

/**
 * Embedded rather than linked, as the DSS serves no static files.
 * A missing resource is a build mistake, so it fails loud.
 */
private val logoDataUri: String by lazy {
  val bytes = ClassLoader.getSystemResourceAsStream(LOGO_RESOURCE)?.use { it.readBytes() }
    ?: error("$LOGO_RESOURCE is missing from the classpath.")
  "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(bytes)
}

/**
 * The page a merchant lands on after approving the app: what happened in their words and the way back to the portal.
 * Then the report for support, collapsed unless something needs looking into.
 * [traceId] names the log lines a support request is about.
 */
fun renderOAuthInstallPage(report: ShopInstallReport, portalUrl: String, traceId: String?): String {
  val outcome = report.outcome()
  val heading = headingOf(outcome, report.shop.normalizedShopifyHost)
  return StringBuilder("<!DOCTYPE html>\n").appendHTML().html {
    lang = "en"
    head {
      meta { charset = "utf-8" }
      meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
      meta { name = "color-scheme"; content = "light" }
      meta { name = "robots"; content = "noindex, nofollow" }
      title { +heading }
      link(rel = "preconnect", href = "https://fonts.googleapis.com")
      link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
      link(rel = "stylesheet", href = INTER_STYLESHEET)
      style { unsafe { raw(STYLE_SHEET) } }
    }
    body {
      div("card") {
        img(alt = "DropNext", src = logoDataUri, classes = "logo")
        h1 { +heading }
        renderMerchantPart(outcome, report)
        p("actions") { a(href = portalUrl, classes = "button") { +"Continue to DropNext" } }
        details {
          if (outcome != ShopInstallOutcome.Connected) open = true
          summary { +"Details for DropNext support" }
          renderReport(report)
        }
        if (traceId != null) {
          p("reference") {
            +"Reference for support: "
            code { +traceId }
          }
        }
        div("footer") {
          p {
            +"DropNext B.V. · "
            supportLink()
          }
        }
      }
    }
  }.toString()
}

private fun headingOf(outcome: ShopInstallOutcome, shopHost: String): String =
  when (outcome) {
    ShopInstallOutcome.Connected -> "DropNext is connected to $shopHost"
    is ShopInstallOutcome.ConnectedWithGaps -> "DropNext is connected to $shopHost, but not completely"
    ShopInstallOutcome.UnknownStore -> "DropNext does not know $shopHost yet"
    ShopInstallOutcome.NotSaved -> "DropNext could not save the connection to $shopHost"
  }

private fun FlowContent.renderMerchantPart(outcome: ShopInstallOutcome, report: ShopInstallReport) {
  when (outcome) {
    ShopInstallOutcome.Connected -> {
      p {
        +"We are now copying your products from Shopify. This can take a while for a large catalog."
        report.productCount?.let { +" ${describeFoundProducts(it)}" }
        +" From now on, orders placed in your Shopify store reach DropNext by themselves."
      }
      p { +"If you installed the app for someone else, you can close this tab." }
    }

    is ShopInstallOutcome.ConnectedWithGaps -> {
      p { +"We are now copying your products from Shopify, but not everything DropNext needs is in place:" }
      div("notice notice-warn") {
        ul {
          if (outcome.missingScopes.isNotEmpty()) {
            li {
              +"Shopify did not grant DropNext all the access it asked for, so some orders or fulfillments will fail. "
              +"Open the install link again and approve the access Shopify asks for."
            }
          }
          if (outcome.failedTopics.isNotEmpty()) {
            li {
              +"Shopify did not accept every notification DropNext needs, "
              +"so some products or orders may not reach DropNext. "
              +"Please contact DropNext support at "
              supportLink()
              +"."
            }
          }
        }
      }
      p { +"The details below say exactly what is missing." }
    }

    ShopInstallOutcome.UnknownStore -> div("notice notice-bad") {
      p {
        +"Shopify approved the app, but there is no store with this Shopify address in DropNext, "
        +"so nothing was connected."
      }
      p {
        +"Add the store in DropNext under "
        em { +"Settings › Stores" }
        +", check that its Shopify address is ${report.shop.normalizedShopifyHost}, "
        +"and then open the install link again."
      }
    }

    ShopInstallOutcome.NotSaved -> div("notice notice-bad") {
      p { +"Shopify approved the app, but DropNext could not save the connection." }
      p { +"Please open the install link again in a few minutes." }
      p {
        +"If that does not help, contact DropNext support at "
        supportLink()
        +", and mention the reference below."
      }
    }
  }
}

/** Shopify stops counting at a cap, past which its count is a lower bound. */
private fun describeFoundProducts(productCount: ProductCount): String {
  val count = String.format(Locale.US, "%,d", productCount.count)
  val products = if (productCount.count == 1) "product" else "products"
  val atLeast = if (productCount.isExact) "" else "at least "
  return "We found $atLeast$count $products in your shop."
}

private fun FlowContent.supportLink() {
  a(href = "mailto:$SUPPORT_ADDRESS") { +SUPPORT_ADDRESS }
}

/** What the page showed before it addressed the merchant, kept for whoever reads it to support them. */
private fun FlowContent.renderReport(report: ShopInstallReport) {
  p { +"Shop: ${report.shop.normalizedShopifyHost} (id ${report.shopId?.value ?: "unknown"})" }
  renderAccessScopes(report.accessScopes)
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

/** The report keeps its count as it was before the page addressed the merchant: ungrouped, and never singular. */
private fun describeProductCount(productCount: ProductCount): String =
  if (productCount.isExact) "${productCount.count}" else "at least ${productCount.count}"

/** A missing scope fails only the operations that need it, so an incomplete grant lists every scope with what it is for. */
private fun FlowContent.renderAccessScopes(report: ShopifyAccessScopeReport) {
  if (report.isComplete) {
    p {
      classes = setOf("status-ok")
      +"All ${ShopifyAccessScope.entries.size} required access scopes granted."
    }
    return
  }
  p {
    classes = setOf("status-bad")
    strong { +"This shop did not grant every access scope the service needs" }
    +"; what needs a missing scope will fail. Open the install link for this shop again and approve the requested access."
  }
  scrollingTable {
    thead {
      tr {
        th { +"Access scope" }
        th { +"Needed for" }
        th { +"Status" }
      }
    }
    tbody {
      ShopifyAccessScope.entries.forEach { scope ->
        tr {
          td { code { +scope.handle } }
          td { +scope.neededFor }
          td {
            if (scope in report.missing) {
              span("status-bad") { strong { +"missing" } }
            } else {
              span("status-ok") { +"granted" }
            }
          }
        }
      }
    }
  }
}

/** The box scrolls, so the table can stay a table, which some screen readers need. */
private fun FlowContent.scrollingTable(block: TABLE.() -> Unit) {
  div("table-scroll") { table { block() } }
}

private fun FlowContent.renderMonolithPersistBlock(outcome: MonolithPersistOutcome) {
  when (outcome) {
    is MonolithPersistOutcome.Persisted -> p {
      classes = setOf("status-ok")
      strong { +"Shopify token saved via monolith" }
      +" (store_id=${outcome.storeId}) and cached in memory - webhooks and routes can use this process immediately."
    }

    is MonolithPersistOutcome.Failed -> p {
      classes = setOf("status-bad")
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
  scrollingTable {
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
                classes = setOf("status-warn")
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
    is WebhookTopicStatus.Active -> span("status-ok") { +"active" }
    is WebhookTopicStatus.Added -> span("status-ok") { strong { +"added" } }
    is WebhookTopicStatus.Updated -> span("status-ok") { strong { +"updated" } }
    is WebhookTopicStatus.Repointed -> span("status-ok") { strong { +"repointed" } }
    is WebhookTopicStatus.Mismatched -> span("status-warn") { +"mismatched" }
    is WebhookTopicStatus.Missing -> span("status-warn") { +"missing" }
    is WebhookTopicStatus.NotApplied -> span("status-bad") { strong { +"not applied" } }
    is WebhookTopicStatus.Failed -> span("status-bad") { strong { +"failed" } }
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
          is ObsoleteSubscriptionRemoval.Deleted -> span("status-ok") { +"deleted" }
          is ObsoleteSubscriptionRemoval.NotAttempted -> span("status-warn") { +"still subscribed" }
          is ObsoleteSubscriptionRemoval.Failed -> span {
            classes = setOf("status-bad")
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
    classes = setOf("status-bad")
    strong { +"Webhook subscriptions that could not be registered or updated:" }
  }
  ul {
    failures.forEach { row ->
      li {
        classes = setOf("status-bad")
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
    +"Make sure your Shopify Partner Dashboard app has Orders API access enabled and that the shop granted "
    +"the access scopes reported above. Reinstall the app after fixing."
  }
}
