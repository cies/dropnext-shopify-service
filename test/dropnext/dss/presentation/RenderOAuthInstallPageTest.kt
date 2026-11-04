package dropnext.dss.presentation

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.ObsoleteWebhookSubscription
import dropnext.dss.domain.ProductCount
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.StoreId
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.testutil.fixture.EVERY_SCOPE_HANDLE
import dropnext.dss.testutil.fixture.shopInstallReport
import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import java.util.Base64
import org.junit.jupiter.api.Test


private const val PORTAL_URL = "https://portal.test/retailer-portal"
private const val TRACE_ID = "trace-0123456789abcdef"

private val unknownStore = MonolithPersistOutcome.Failed(httpStatus = 404, detail = "Store not found.", storeNotFound = true)
private val notSaved = MonolithPersistOutcome.Failed(httpStatus = 503, detail = "upstream timeout", storeNotFound = false)
private val failedTopic = WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Failed("denied"))

/** What the page showed before it was written for the merchant: everything support reads, collapsed. */
private fun detailsOf(html: String): String =
  html.substringAfter("<details").substringBefore("</details>")

/** What the merchant reads first: the card up to the collapsed report. */
private fun merchantPartOf(html: String): String =
  html.substringAfter("<h1>").substringBefore("<details")

class RenderOAuthInstallPageTest {

  private fun renderBase(
    monolithPersist: MonolithPersistOutcome = MonolithPersistOutcome.Persisted(storeId = StoreId(1L)),
    topics: List<WebhookTopicRegistration> = emptyList(),
    obsolete: List<ObsoleteWebhookSubscription> = emptyList(),
    productCount: ProductCount? = ProductCount(count = 3, isExact = true),
    grantedScopeHandles: List<String> = EVERY_SCOPE_HANDLE,
    traceId: String? = TRACE_ID,
  ): String =
    renderOAuthInstallPage(
      shopInstallReport(
        monolithPersist = monolithPersist,
        topics = topics,
        obsolete = obsolete,
        productCount = productCount,
        grantedScopeHandles = grantedScopeHandles,
      ),
      portalUrl = PORTAL_URL,
      traceId = traceId,
    )

  // ---------- the merchant's part, per outcome ----------

  @Test
  fun `a connected install says so in the heading and the title`() {
    val html = renderBase()
    val heading = "DropNext is connected to acme.myshopify.com"
    assert("<h1>$heading</h1>" in html)
    assert("<title>$heading</title>" in html)
    assert("From now on, orders placed in your Shopify store reach DropNext by themselves." in html)
    assert("If you installed the app for someone else, you can close this tab." in html)
  }

  @Test
  fun `an install with gaps says so in the heading and the title`() {
    val html = renderBase(grantedScopeHandles = EVERY_SCOPE_HANDLE - "write_fulfillments")
    val heading = "DropNext is connected to acme.myshopify.com, but not completely"
    assert("<h1>$heading</h1>" in html)
    assert("<title>$heading</title>" in html)
    assert("but not everything DropNext needs is in place:" in html)
    assert("The details below say exactly what is missing." in html)
  }

  @Test
  fun `a missing scope is a gap that asks to approve the access again`() {
    val merchantPart = merchantPartOf(renderBase(grantedScopeHandles = EVERY_SCOPE_HANDLE - "write_fulfillments"))
    assert("Shopify did not grant DropNext all the access it asked for" in merchantPart)
    assert("Open the install link again and approve the access Shopify asks for." in merchantPart)
    assert("Shopify did not accept every notification DropNext needs" !in merchantPart)
  }

  @Test
  fun `a failed topic is a gap that asks to contact support`() {
    val merchantPart = merchantPartOf(renderBase(topics = listOf(failedTopic)))
    assert("Shopify did not accept every notification DropNext needs" in merchantPart)
    assert("""<a href="mailto:support@dropnext.com">support@dropnext.com</a>""" in merchantPart)
    assert("Shopify did not grant DropNext all the access it asked for" !in merchantPart)
  }

  @Test
  fun `a missing scope and a failed topic at once are two items of the gaps block`() {
    val html = renderBase(grantedScopeHandles = EVERY_SCOPE_HANDLE - "read_orders", topics = listOf(failedTopic))
    val gapsBlock = html.substringAfter("""<div class="notice notice-warn">""").substringBefore("</div>")
    assert(Regex("<li>").findAll(gapsBlock).count() == 2)
  }

  @Test
  fun `an unknown store says so in the heading and the title and asks to add it first`() {
    val html = renderBase(monolithPersist = unknownStore)
    val heading = "DropNext does not know acme.myshopify.com yet"
    assert("<h1>$heading</h1>" in html)
    assert("<title>$heading</title>" in html)
    assert("there is no store with this Shopify address in DropNext, so nothing was connected." in html)
    assert("check that its Shopify address is acme.myshopify.com, and then open the install link again." in html)
  }

  @Test
  fun `a token that was not saved says so in the heading and the title and asks to try again`() {
    val html = renderBase(monolithPersist = notSaved)
    val heading = "DropNext could not save the connection to acme.myshopify.com"
    assert("<h1>$heading</h1>" in html)
    assert("<title>$heading</title>" in html)
    assert("Please open the install link again in a few minutes." in html)
    assert("and mention the reference below." in html)
  }

  /** A merchant who has to act needs the tab; only a finished install may be left behind. */
  @Test
  fun `only a connected install says the tab can be closed`() {
    assert("you can close this tab" !in renderBase(topics = listOf(failedTopic)))
    assert("you can close this tab" !in renderBase(monolithPersist = unknownStore))
    assert("you can close this tab" !in renderBase(monolithPersist = notSaved))
  }

  /** The palette's amber: the store is connected, but something needs doing. */
  @Test
  fun `the gaps notice is a warning`() {
    val merchantPart = merchantPartOf(renderBase(topics = listOf(failedTopic)))
    assert("""<div class="notice notice-warn">""" in merchantPart)
    assert("notice-bad" !in merchantPart)
  }

  /** The palette's red: nothing was connected. */
  @Test
  fun `the unknown store and the not saved notices are bad news`() {
    val unknownStorePart = merchantPartOf(renderBase(monolithPersist = unknownStore))
    assert("""<div class="notice notice-bad">""" in unknownStorePart)
    assert("notice-warn" !in unknownStorePart)
    val notSavedPart = merchantPartOf(renderBase(monolithPersist = notSaved))
    assert("""<div class="notice notice-bad">""" in notSavedPart)
    assert("notice-warn" !in notSavedPart)
  }

  @Test
  fun `every outcome links back to the portal it was given`() {
    val button = """<a href="$PORTAL_URL" class="button">Continue to DropNext</a>"""
    assert(button in renderBase())
    assert(button in renderBase(topics = listOf(failedTopic)))
    assert(button in renderBase(monolithPersist = unknownStore))
    assert(button in renderBase(monolithPersist = notSaved))
  }

  @Test
  fun `the product line reads the count as Shopify counted it`() {
    assert("We found 1,234 products in your shop." in renderBase(productCount = ProductCount(count = 1234, isExact = true)))
  }

  @Test
  fun `the product line reads a count past Shopify's cap as a lower bound`() {
    val html = renderBase(productCount = ProductCount(count = 10000, isExact = false))
    assert("We found at least 10,000 products in your shop." in html)
  }

  @Test
  fun `the product line is left out when the count failed`() {
    assert("We found" !in renderBase(productCount = null))
  }

  @Test
  fun `the product line names a single product in the singular`() {
    assert("We found 1 product in your shop." in renderBase(productCount = ProductCount(count = 1, isExact = true)))
  }

  // ---------- the report for support, collapsed ----------

  @Test
  fun `the details are closed for a connected install`() {
    val html = renderBase()
    assert("<details><summary>Details for DropNext support</summary>" in html)
  }

  @Test
  fun `the details are open for every other outcome`() {
    val open = """<details open="open"><summary>Details for DropNext support</summary>"""
    assert(open in renderBase(topics = listOf(failedTopic)))
    assert(open in renderBase(monolithPersist = unknownStore))
    assert(open in renderBase(monolithPersist = notSaved))
  }

  @Test
  fun `the report no longer has a heading of its own`() {
    val html = renderBase()
    assert("App installed" !in html)
    assert(Regex("<h1>").findAll(html).count() == 1)
  }

  @Test
  fun `the details name the shop and its id`() {
    assert("Shop: acme.myshopify.com (id 9988)" in detailsOf(renderBase()))
  }

  /** Shopify stops counting at a cap (10,000 by default), so a count it did not finish is a lower bound. */
  @Test
  fun `the product count is shown as counted, and as a lower bound past Shopify's cap`() {
    assert("Products in the shop: 3" in detailsOf(renderBase()))
    val lowerBound = renderBase(productCount = ProductCount(count = 10000, isExact = false))
    assert("Products in the shop: at least 10000" in detailsOf(lowerBound))
  }

  @Test
  fun `a failed product count reads as unknown rather than zero`() {
    assert("Products in the shop: unknown (lookup failed)" in detailsOf(renderBase(productCount = null)))
  }

  @Test
  fun `escapes html-significant characters in failure error messages`() {
    val html = renderBase(
      topics = listOf(WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Failed("<img src=x onerror=alert(1)>"))),
    )
    assert("<img src=x onerror=alert(1)>" !in html)
    assert("&lt;img src=x onerror=alert(1)&gt;" in detailsOf(html))
  }

  @Test
  fun `a complete grant says every required access scope is granted`() {
    val html = renderBase()
    assert("All ${ShopifyAccessScope.entries.size} required access scopes granted." in detailsOf(html))
    assert("did not grant every access scope" !in html)
  }

  /** A missing scope fails only the operations that need it, so the page names each scope with what it is for. */
  @Test
  fun `a grant without a required scope names it, what it is needed for and that it is missing`() {
    val details = detailsOf(renderBase(grantedScopeHandles = EVERY_SCOPE_HANDLE - "write_fulfillments"))
    assert("This shop did not grant every access scope the service needs" in details)
    assert("<td><code>write_fulfillments</code></td>" in details)
    assert(ShopifyAccessScope.WRITE_FULFILLMENTS.neededFor in details)
    assert(Regex("<strong>missing</strong>").findAll(details).count() == 1)
    assert(Regex(">granted<").findAll(details).count() == ShopifyAccessScope.entries.size - 1)
    assert("All ${ShopifyAccessScope.entries.size} required access scopes granted." !in details)
  }

  @Test
  fun `monolith persisted renders the success notice with storeId`() {
    val details = detailsOf(renderBase(monolithPersist = MonolithPersistOutcome.Persisted(storeId = StoreId(42L))))
    assert("Shopify token saved via monolith" in details)
    assert("store_id=42" in details)
  }

  @Test
  fun `monolith failed renders status and detail`() {
    val details = detailsOf(renderBase(monolithPersist = notSaved))
    assert("HTTP status 503" in details)
    assert("upstream timeout" in details)
  }

  /** The merchant's part no longer says it, but support still needs to know the token is cached. */
  @Test
  fun `monolith failed still says in the details that the token is cached in memory only`() {
    val html = renderBase(monolithPersist = notSaved)
    assert("Token is cached in this server's memory only." in detailsOf(html))
    assert("cached in this server" !in merchantPartOf(html))
  }

  @Test
  fun `monolith unreachable renders as no response`() {
    val unreachable = MonolithPersistOutcome.Failed(httpStatus = null, detail = null, storeNotFound = false)
    val details = detailsOf(renderBase(monolithPersist = unreachable))
    assert("no response" in details)
    assert("Details: " !in details)
  }

  @Test
  fun `monolith failed truncates very long detail`() {
    val longDetail = MonolithPersistOutcome.Failed(httpStatus = 500, detail = "x".repeat(800), storeNotFound = false)
    val html = renderBase(monolithPersist = longDetail)
    assert("x".repeat(400) in detailsOf(html))
    assert("x".repeat(401) !in html)
  }

  @Test
  fun `the summary line counts active, added, updated, repointed, failed and stale`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "PRODUCTS_CREATE"))),
        WebhookTopicRegistration("PRODUCTS_UPDATE", WebhookTopicStatus.Added(webhookSubscriptionStatus(2, "PRODUCTS_UPDATE"))),
        WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Failed("scope missing")),
        WebhookTopicRegistration(
          "ORDERS_UPDATED",
          WebhookTopicStatus.Added(webhookSubscriptionStatus(3, "ORDERS_UPDATED")),
          stale = listOf(webhookSubscriptionStatus(4, "ORDERS_UPDATED", uri = "https://old.example/webhooks/shopify")),
        ),
      ),
    )
    assert("1 already active, 2 added, 0 updated and 0 repointed from another URL in this install, 1 failed, 1 pointing elsewhere, 0 deleted for a topic the service no longer handles." in detailsOf(html))
  }

  @Test
  fun `a mismatched row names the payload fields Shopify sends and the ones the topic declares`() {
    val details = detailsOf(
      renderBase(
        topics = listOf(
          WebhookTopicRegistration(
            "ORDERS_CREATE",
            WebhookTopicStatus.Mismatched(webhookSubscriptionStatus(1, "ORDERS_CREATE"), expectedIncludeFields = listOf("id", "admin_graphql_api_id")),
          ),
        ),
      ),
    )
    assert(">mismatched<" in details)
    assert("gid://shopify/WebhookSubscription/1" in details)
    assert("<code>all fields</code>" in details)
    assert("<code>id, admin_graphql_api_id</code>" in details)
    assert(", filter: " !in details)
    assert(", format: " !in details)
  }

  @Test
  fun `a mismatched row names a filter and a format other than json`() {
    val details = detailsOf(
      renderBase(
        topics = listOf(
          WebhookTopicRegistration(
            "PRODUCTS_UPDATE",
            WebhookTopicStatus.Mismatched(
              webhookSubscriptionStatus(1, "PRODUCTS_UPDATE", filter = "vendor:Acme", format = "XML"),
              expectedIncludeFields = emptyList(),
            ),
          ),
        ),
      ),
    )
    assert(", filter: <code>vendor:Acme</code>" in details)
    assert(", format: <code>XML</code>" in details)
  }

  @Test
  fun `an updated and a repointed row show what this install changed`() {
    val details = detailsOf(
      renderBase(
        topics = listOf(
          WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Updated(webhookSubscriptionStatus(1, "ORDERS_CREATE"))),
          WebhookTopicRegistration(
            "PRODUCTS_UPDATE",
            WebhookTopicStatus.Repointed(webhookSubscriptionStatus(2, "PRODUCTS_UPDATE"), previousUri = "https://old.example/webhooks/shopify"),
          ),
        ),
      ),
    )
    assert(">updated<" in details)
    assert(">repointed<" in details)
    assert("gid://shopify/WebhookSubscription/2" in details)
    assert("https://old.example/webhooks/shopify" in details)
    assert("0 already active, 0 added, 1 updated and 1 repointed from another URL in this install, 0 failed, 0 pointing elsewhere, 0 deleted for a topic the service no longer handles." in details)
  }

  /** An update Shopify accepted without applying it is a failure the reader acts on, shown with what Shopify kept. */
  @Test
  fun `a not applied row compares the fields Shopify kept with the declared ones and is listed as a failure`() {
    val details = detailsOf(
      renderBase(
        topics = listOf(
          WebhookTopicRegistration(
            "PRODUCTS_CREATE",
            WebhookTopicStatus.NotApplied(webhookSubscriptionStatus(1, "PRODUCTS_CREATE", includeFields = listOf("id")), expectedIncludeFields = emptyList()),
          ),
        ),
      ),
    )
    assert(">not applied<" in details)
    assert("<code>id</code>" in details)
    assert("<code>all fields</code>" in details)
    assert("Webhook subscriptions that could not be registered or updated" in details)
    assert("Shopify accepted the update but did not apply it." in details)
    assert("0 repointed from another URL in this install, 1 failed" in details)
  }

  @Test
  fun `one table row per topic with its status, subscription id and stale uri`() {
    val details = detailsOf(
      renderBase(
        topics = listOf(
          WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "PRODUCTS_CREATE"))),
          WebhookTopicRegistration(
            "ORDERS_CREATE",
            WebhookTopicStatus.Missing,
            stale = listOf(webhookSubscriptionStatus(2, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify")),
          ),
        ),
      ),
    )
    assert("<td><code>PRODUCTS_CREATE</code></td>" in details)
    assert("gid://shopify/WebhookSubscription/1" in details)
    assert(">active<" in details)
    assert(">missing<" in details)
    assert("https://old.example/webhooks/shopify" in details)
    assert("gid://shopify/WebhookSubscription/2" in details)
  }

  @Test
  fun `no failure block when there are no failures`() {
    val html = renderBase(topics = listOf(WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "PRODUCTS_CREATE")))))
    assert("Webhook subscriptions that could not be registered or updated" !in html)
  }

  @Test
  fun `failure block lists each failed topic`() {
    val html = renderBase(
      topics = listOf(
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Failed("permission denied")),
        WebhookTopicRegistration("ORDERS_UPDATED", WebhookTopicStatus.Failed("scope missing")),
      ),
    )
    assert("Webhook subscriptions that could not be registered or updated:" in detailsOf(html))
    // The topic table above names every topic too; only the block after the heading proves the failures are listed.
    val failureBlock = detailsOf(html).substringAfter("Webhook subscriptions that could not be registered or updated:")
    assert("PRODUCTS_CREATE" in failureBlock)
    assert("ORDERS_UPDATED" in failureBlock)
    assert("permission denied" in failureBlock)
    assert("scope missing" in failureBlock)
    assert("Reinstall the app after fixing." in failureBlock)
    assert("the access scopes reported above" in failureBlock)
  }

  @Test
  fun `names what was subscribed for a topic no longer handled, deleted or not, with the deletion error escaped`() {
    val html = renderBase(
      obsolete = listOf(
        ObsoleteWebhookSubscription(webhookSubscriptionStatus(3, "ORDERS_UPDATED"), ObsoleteSubscriptionRemoval.Deleted),
        ObsoleteWebhookSubscription(webhookSubscriptionStatus(4, "CUSTOMERS_CREATE"), ObsoleteSubscriptionRemoval.Failed("<denied>")),
      ),
    )
    val details = detailsOf(html)
    assert("1 deleted for a topic the service no longer handles." in details)
    assert("Subscriptions at the webhook callback URL for a topic the service no longer handles:" in details)
    assert("gid://shopify/WebhookSubscription/3" in details)
    assert("deletion failed: " in details)
    assert("&lt;denied&gt;" in details)
    assert("<denied>" !in html)
  }

  @Test
  fun `says nothing about topics no longer handled when nothing was subscribed for one`() {
    assert("for a topic the service no longer handles:" !in renderBase())
  }

  @Test
  fun `the status words carry their class`() {
    val granted = detailsOf(renderBase(grantedScopeHandles = EVERY_SCOPE_HANDLE - "write_fulfillments"))
    assert("""<span class="status-ok">granted</span>""" in granted)
    assert("""<span class="status-bad"><strong>missing</strong></span>""" in granted)
    val failed = detailsOf(renderBase(topics = listOf(failedTopic)))
    assert("""<span class="status-bad"><strong>failed</strong></span>""" in failed)
  }

  // ---------- the reference for support ----------

  @Test
  fun `the reference line shows the trace id below the details`() {
    val html = renderBase(monolithPersist = notSaved)
    val reference = "Reference for support: <code>$TRACE_ID</code>"
    assert(reference in html.substringAfter("</details>"))
  }

  @Test
  fun `the reference line is left out without a trace id`() {
    assert("Reference for support" !in renderBase(traceId = null))
  }

  // ---------- the document and its look ----------

  @Test
  fun `renders a complete html document`() {
    val html = renderBase()
    assert(html.startsWith("<!DOCTYPE html>"))
    assert("""<html lang="en">""" in html)
    assert("""<meta charset="utf-8">""" in html)
    assert("""<meta name="viewport" content="width=device-width, initial-scale=1">""" in html)
    assert("""<meta name="color-scheme" content="light">""" in html)
  }

  @Test
  fun `robots meta is set to noindex nofollow`() {
    assert("""<meta name="robots" content="noindex, nofollow">""" in renderBase())
  }

  @Test
  fun `the page loads Inter as the portal does`() {
    val html = renderBase()
    assert("""<link href="https://fonts.googleapis.com" rel="preconnect">""" in html)
    assert("""<link href="https://fonts.gstatic.com" rel="preconnect" crossorigin="">""" in html)
    val stylesheet = """<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400..700&amp;display=swap" rel="stylesheet">"""
    assert(stylesheet in html)
  }

  @Test
  fun `the logo is an image of the checked-in svg`() {
    val html = renderBase()
    val dataUriPrefix = "data:image/svg+xml;base64,"
    val logo = Regex("""<img alt="DropNext" src="${Regex.escape(dataUriPrefix)}([A-Za-z0-9+/=]+)" class="logo">""").find(html)
    assert(logo != null)
    val decoded = Base64.getDecoder().decode(logo!!.groupValues[1])
    assert(decoded.contentEquals(logoResourceBytes()))
  }

  /** A logo lost from `src/resources` would otherwise fail only the first install after a deploy. */
  @Test
  fun `the logo resource is on the classpath and is an svg`() {
    val text = logoResourceBytes().decodeToString()
    assert(text.trimStart().startsWith("<svg"))
  }

  /** Every rule lives in the one style sheet, so a class is all an element carries. */
  @Test
  fun `no element carries a style attribute`() {
    val everyOutcome = listOf(
      renderBase(),
      renderBase(grantedScopeHandles = EVERY_SCOPE_HANDLE - "read_orders", topics = listOf(failedTopic)),
      renderBase(monolithPersist = unknownStore),
      renderBase(monolithPersist = notSaved),
    )
    everyOutcome.forEach { html -> assert(" style=" !in html) }
  }

  /** A table made a block loses its table semantics for some screen readers, so a box around it does the scrolling. */
  @Test
  fun `every details table sits in a box that scrolls sideways`() {
    val details = detailsOf(
      renderBase(
        grantedScopeHandles = EVERY_SCOPE_HANDLE - "write_fulfillments",
        topics = listOf(WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "PRODUCTS_CREATE")))),
      ),
    )
    val tables = Regex("<table>").findAll(details).count()
    assert(tables == 2)
    assert(Regex("""<div class="table-scroll">\s*<table>""").findAll(details).count() == tables)
  }

  /**
   * A cell whose code may break anywhere shrinks to one character, so the table always fits and never scrolls.
   * Only a code outside the tables (the callback URL, the reference) breaks anywhere.
   */
  @Test
  fun `the style sheet keeps tables tables and breaks only codes outside them`() {
    val sheet = renderBase().substringAfter("<style>").substringBefore("</style>")
    assert(".table-scroll { max-width: 100%; overflow-x: auto; }" in sheet)
    assert(sheet.lines().filter { "table" in it }.none { "display: block" in it })
    assert(sheet.lines().single { "break-all" in it }.startsWith("details p code, .reference code {"))
  }

  @Test
  fun `the footer names the sender and links the support address`() {
    val footer = renderBase().substringAfter("""<div class="footer">""")
    assert("DropNext B.V. · " in footer)
    assert("""<a href="mailto:support@dropnext.com">support@dropnext.com</a>""" in footer)
  }

  private fun logoResourceBytes(): ByteArray =
    ClassLoader.getSystemResourceAsStream("dropnext-logo.svg")?.use { it.readBytes() }
      ?: error("dropnext-logo.svg is missing from the test classpath.")
}
