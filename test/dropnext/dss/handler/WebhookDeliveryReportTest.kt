package dropnext.dss.handler

import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithErrorBody
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.workflow.WebhookMirrorOutcome
import dropnext.dss.workflow.WebhookSkipReason
import kotlin.test.Test


private val acme = ShopDomain.parse("acme.myshopify.com")!!

/**
 * The log line and the response body are two renderings of one report, so both are pinned here
 * against the same outcomes; the level is pinned because it is what an alert is written against.
 */
class WebhookDeliveryReportTest {

  private fun report(outcome: WebhookMirrorOutcome, shop: ShopDomain? = acme, lagMillis: Long? = 840L) =
    WebhookDeliveryReport(
      topic = "orders/create",
      shop = shop,
      webhookId = "b54557e4-0f2d-4d7c-8c45-8d2a5c4d6f7e",
      lagMillis = lagMillis,
      tookMillis = 612L,
      outcome = outcome,
    )

  @Test
  fun `a mirrored delivery is an info line with the lag and the duration`() {
    val report = report(WebhookMirrorOutcome.Mirrored)
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.INFO)
    assert(
      report.logLine(200) ==
        "Webhook done topic=orders/create shop=acme.myshopify.com webhook_id=b54557e4-0f2d-4d7c-8c45-8d2a5c4d6f7e " +
        "outcome=mirrored lag_ms=840 took_ms=612 answered=200",
    )
    assert(report.toResponse("trace-1") == WebhookDeliveryResponse(outcome = "mirrored", traceId = "trace-1"))
  }

  @Test
  fun `a skipped delivery names its reason in both renderings`() {
    val report = report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.TOPIC_NOT_MIRRORED), lagMillis = null)
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.INFO)
    assert("outcome=skipped reason=topic_not_mirrored" in report.logLine(200))
    assert("lag_ms" !in report.logLine(200))
    assert(report.toResponse(null) == WebhookDeliveryResponse(outcome = "skipped", reason = "topic_not_mirrored"))
  }

  /** The summary is the one line a delivery leaves, so a skip that repeats until someone acts has to be loud there. */
  @Test
  fun `a skip that needs a human is an error line, one worth a look a warning, the rest information`() {
    assert(report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_ADMIN_TOKEN)).logLevel == WebhookDeliveryReport.LogLevel.ERROR)
    assert(report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_SHOP_DOMAIN)).logLevel == WebhookDeliveryReport.LogLevel.ERROR)
    assert(report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_RESOURCE_ID)).logLevel == WebhookDeliveryReport.LogLevel.WARN)
    assert(report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.PRODUCT_GONE)).logLevel == WebhookDeliveryReport.LogLevel.INFO)
    assert(report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_MAPPABLE_LINES)).logLevel == WebhookDeliveryReport.LogLevel.INFO)
  }

  @Test
  fun `a transient failure is a warning that says Shopify was asked to redeliver`() {
    val report = report(WebhookMirrorOutcome.MonolithFailed(MonolithError.Transport("connection refused")))
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.WARN)
    assert("outcome=failed transient=true error=monolith_transport" in report.logLine(502))
    assert(report.logLine(502).endsWith("answered=502"))
  }

  @Test
  fun `a permanent failure is an error, since only a human can fix it`() {
    val refused = MonolithError.Rejected(400, "bad", MonolithErrorBody(message = "bad", code = null, monolithTraceId = null))
    val report = report(WebhookMirrorOutcome.MonolithFailed(refused))
    assert(report.logLevel == WebhookDeliveryReport.LogLevel.ERROR)
    assert("outcome=failed transient=false error=monolith_400" in report.logLine(200))
    assert(report.toResponse("trace-2") == WebhookDeliveryResponse(outcome = "failed", error = "monolith_400", traceId = "trace-2"))
  }

  @Test
  fun `the error label names the Shopify failure class and status, never its message`() {
    val throttled = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.HttpError(429)))
    assert(throttled.errorLabel == "shopify_http_429")
    val rejected = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.TokenRejected(401)))
    assert(rejected.errorLabel == "shopify_token_rejected")
    assert(rejected.logLevel == WebhookDeliveryReport.LogLevel.ERROR)
    val gone = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.NotFound("order 1 not found")))
    assert(gone.errorLabel == "shopify_not_found")
    assert("order 1 not found" !in gone.logLine(200))
  }

  /** Neither was refused: the token source did not answer, or the work did not finish in time. Shopify is asked to redeliver both. */
  @Test
  fun `an unavailable token and a blown time budget are transient failures with their own labels`() {
    val unavailable = report(WebhookMirrorOutcome.TokenUnavailable)
    assert(unavailable.logLevel == WebhookDeliveryReport.LogLevel.WARN)
    assert("outcome=failed transient=true error=token_unavailable" in unavailable.logLine(502))
    assert("outcome=failed transient=true error=timed_out" in report(WebhookMirrorOutcome.TimedOut).logLine(502))
    assert("outcome=failed transient=true error=overloaded" in report(WebhookMirrorOutcome.Overloaded).logLine(502))
    assert(report(WebhookMirrorOutcome.Overloaded).logLevel == WebhookDeliveryReport.LogLevel.WARN)
  }

  @Test
  fun `a Graphql failure's codes are on the log line, and a permanent one is an error`() {
    val denied = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("Access denied", codes = listOf("ACCESS_DENIED"))))
    assert(denied.logLevel == WebhookDeliveryReport.LogLevel.ERROR)
    assert("transient=false error=shopify_graphql codes=ACCESS_DENIED" in denied.logLine(200))
    assert(denied.toResponse(null) == WebhookDeliveryResponse(outcome = "failed", error = "shopify_graphql"))
  }

  @Test
  fun `an unreadable Shopify answer is labelled without the body its decoder quoted`() {
    val unreadable = report(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.Undecodable("JSON input: {\"email\":\"jane@example.com\"}")))
    assert(unreadable.errorLabel == "shopify_undecodable")
    assert("jane@example.com" !in unreadable.logLine(200))
  }

  @Test
  fun `a delivery without a shop or a webhook id still renders`() {
    val report = report(WebhookMirrorOutcome.Skipped(WebhookSkipReason.NO_SHOP_DOMAIN), shop = null).copy(webhookId = null)
    assert("shop=- webhook_id=- outcome=skipped reason=no_shop_domain" in report.logLine(200))
  }
}
