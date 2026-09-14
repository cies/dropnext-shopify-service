package dropnext.dss.handler

import dropnext.dss.lib.monolith.errorLabel
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.errorLabel
import dropnext.dss.workflow.WebhookMirrorOutcome
import dropnext.dss.workflow.WebhookSkipReason
import dropnext.dss.workflow.isTransient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * One verified webhook delivery and what became of it, for the two readers who never see each other's
 * channel: the operator reading the log, and the engineer reading Shopify's delivery log, which keeps
 * the response body of every attempt. Both get the same facts from the same object, so they cannot
 * disagree: [logLine] for the one, [toResponse] for the other, and [logLevel] for how loud the line is.
 */
data class WebhookDeliveryReport(
  val topic: String,
  /** Shopify's `X-Shopify-Webhook-Id`: the same id on two lines is a redelivery, the only visibility into Shopify's retries. */
  val webhookId: String?,
  /** Receipt time minus Shopify's `X-Shopify-Triggered-At`, when the header parsed. */
  val lagMillis: Long?,
  val tookMillis: Long,
  val outcome: WebhookMirrorOutcome,
) {

  val outcomeLabel: String
    get() = when (outcome) {
      is WebhookMirrorOutcome.Mirrored -> "mirrored"
      is WebhookMirrorOutcome.Skipped -> "skipped"
      is WebhookMirrorOutcome.ShopifyFailed, is WebhookMirrorOutcome.MonolithFailed,
      is WebhookMirrorOutcome.TokenUnavailable, is WebhookMirrorOutcome.TimedOut, is WebhookMirrorOutcome.Overloaded,
        -> "failed"
    }

  /** A short, countable name for what failed; never the upstream's message, which may be long or bulky. */
  val errorLabel: String?
    get() = when (val outcome = outcome) {
      is WebhookMirrorOutcome.Mirrored, is WebhookMirrorOutcome.Skipped -> null
      is WebhookMirrorOutcome.ShopifyFailed -> outcome.error.errorLabel
      is WebhookMirrorOutcome.MonolithFailed -> outcome.error.errorLabel
      is WebhookMirrorOutcome.TokenUnavailable -> "token_unavailable"
      is WebhookMirrorOutcome.TimedOut -> "timed_out"
      is WebhookMirrorOutcome.Overloaded -> "overloaded"
    }

  /** Shopify's `extensions.code` values (`THROTTLED`, `ACCESS_DENIED`): as countable as the label, and they say which Graphql error it was. */
  val graphqlErrorCodes: List<String>
    get() = ((outcome as? WebhookMirrorOutcome.ShopifyFailed)?.error as? ShopifyError.GraphqlError)?.codes.orEmpty()

  val skipReasonLabel: String?
    get() = (outcome as? WebhookMirrorOutcome.Skipped)?.reason?.name?.lowercase()

  /**
   * Chosen by what the reader has to do: nothing for a mirrored delivery or a skip that is the normal shape of things,
   * act on a skip that repeats for every delivery of the shop until someone does (no token, no shop), wait for a
   * transient failure (Shopify redelivers it), act for a permanent one (only a human can fix a refused token or a
   * refused order). This is the one line a delivery leaves in the log, so its level is what an alert reads.
   */
  val logLevel: LogLevel
    get() = when (val outcome = outcome) {
      is WebhookMirrorOutcome.Mirrored -> LogLevel.INFO
      is WebhookMirrorOutcome.Skipped -> outcome.reason.logLevel
      is WebhookMirrorOutcome.ShopifyFailed, is WebhookMirrorOutcome.MonolithFailed,
      is WebhookMirrorOutcome.TokenUnavailable, is WebhookMirrorOutcome.TimedOut, is WebhookMirrorOutcome.Overloaded,
        -> if (outcome.isTransient) LogLevel.WARN else LogLevel.ERROR
    }

  enum class LogLevel { INFO, WARN, ERROR }

  /** The one summary line per delivery, in the `key=value` style Logflare queries are written against. */
  fun logLine(answeredStatus: Int): String = buildString {
    append("Webhook done topic=").append(topic)
    append(" webhook_id=").append(webhookId ?: "-")
    append(" outcome=").append(outcomeLabel)
    skipReasonLabel?.let { append(" reason=").append(it) }
    errorLabel?.let { append(" transient=").append(outcome.isTransient).append(" error=").append(it) }
    if (graphqlErrorCodes.isNotEmpty()) append(" codes=").append(graphqlErrorCodes.joinToString(","))
    lagMillis?.let { append(" lag_ms=").append(it) }
    append(" took_ms=").append(tookMillis)
    append(" answered=").append(answeredStatus)
  }

  fun toResponse(traceId: String?): WebhookDeliveryResponse =
    WebhookDeliveryResponse(outcome = outcomeLabel, reason = skipReasonLabel, error = errorLabel, traceId = traceId)
}

/** A skip nobody has to act on is information; one that keeps happening until someone acts is an error. */
private val WebhookSkipReason.logLevel: WebhookDeliveryReport.LogLevel
  get() = when (this) {
    WebhookSkipReason.NO_SHOP_DOMAIN, WebhookSkipReason.NO_ADMIN_TOKEN -> WebhookDeliveryReport.LogLevel.ERROR
    WebhookSkipReason.NO_RESOURCE_ID -> WebhookDeliveryReport.LogLevel.WARN
    WebhookSkipReason.PRODUCT_GONE, WebhookSkipReason.NO_MAPPABLE_LINES, WebhookSkipReason.TOPIC_NOT_MIRRORED,
      -> WebhookDeliveryReport.LogLevel.INFO
  }

/**
 * The body of a `200` to Shopify. Shopify ignores it but stores it with the delivery, so whoever opens
 * a shop's webhook log in the Partner Dashboard reads why a delivery was skipped or refused, and has
 * the trace id that finds our log line. A transient failure is a `502` with the usual error body.
 */
@Serializable
data class WebhookDeliveryResponse(
  val outcome: String,

  val reason: String? = null,

  val error: String? = null,

  @SerialName("trace_id")
  val traceId: String? = null,
)
