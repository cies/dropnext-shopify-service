package dropnext.dss.domain


/**
 * One row per webhook topic the service handles, answering the three questions the install page and
 * the readiness check are asked: is the shop subscribed at our callback URL delivering as the topic
 * declares, did this run change that, and if it could not, why. [WebhookTopicRegistration.stale]
 * lists subscriptions for the same topic that point elsewhere (an earlier tunnel, another environment) and
 * still receive the deliveries. After an install it holds what could not be repointed to our URL: those beside
 * a subscription already there, all but the first of several, and any that is not an HTTPS address.
 */
data class WebhookRegistrationReport(val topics: List<WebhookTopicRegistration>) {
  val activeCount: Int get() = topics.count { it.status is WebhookTopicStatus.Active }
  val addedCount: Int get() = topics.count { it.status is WebhookTopicStatus.Added }
  val updatedCount: Int get() = topics.count { it.status is WebhookTopicStatus.Updated }
  val repointedCount: Int get() = topics.count { it.status is WebhookTopicStatus.Repointed }
  val missingCount: Int get() = topics.count { it.status is WebhookTopicStatus.Missing }
  val staleCount: Int get() = topics.sumOf { it.stale.size }
  val failures: List<WebhookTopicRegistration> get() = topics.filter { it.status is WebhookTopicStatus.Unsuccessful }
}

/** [topic] is the Admin API enum name (`PRODUCTS_CREATE`). */
data class WebhookTopicRegistration(
  val topic: String,
  val status: WebhookTopicStatus,
  val stale: List<WebhookSubscriptionStatus> = emptyList(),
)

sealed interface WebhookTopicStatus {
  /** Subscribed at our callback URL delivering as the topic declares before this run; nothing was sent to Shopify for it. */
  data class Active(val subscription: WebhookSubscriptionStatus) : WebhookTopicStatus

  /** Subscribed by this run. */
  data class Added(val subscription: WebhookSubscriptionStatus) : WebhookTopicStatus

  /** Subscribed at our callback URL delivering otherwise before this run; this run set what the topic declares. */
  data class Updated(val subscription: WebhookSubscriptionStatus) : WebhookTopicStatus

  /** Subscribed at [previousUri] before this run; this run repointed that subscription to our callback URL. */
  data class Repointed(val subscription: WebhookSubscriptionStatus, val previousUri: String) : WebhookTopicStatus

  /**
   * Subscribed at our callback URL, but not delivering as the topic declares (other payload fields, a filter, or a format
   * other than JSON), and this run did not try to change that: a read-only scan. [expectedIncludeFields] uses Shopify's
   * encoding, like the subscription's: `[]` is the full payload.
   */
  data class Mismatched(val subscription: WebhookSubscriptionStatus, val expectedIncludeFields: List<String>) : WebhookTopicStatus

  /** Not subscribed at our callback URL, and this run did not try: a read-only scan. */
  data object Missing : WebhookTopicStatus

  /** What [WebhookRegistrationReport.failures] lists: this run tried, and the topic is still not subscribed the way it declares. */
  sealed interface Unsuccessful : WebhookTopicStatus

  /**
   * This run sent an update Shopify accepted without applying it; [answered] is the subscription as Shopify reported it
   * afterwards, and [expectedIncludeFields] uses its encoding: `[]` is the full payload.
   */
  data class NotApplied(val answered: WebhookSubscriptionStatus, val expectedIncludeFields: List<String>) : Unsuccessful

  /** This run tried and Shopify refused; [error] is its user error, which the reader is about to act on. */
  data class Failed(val error: String) : Unsuccessful
}

/**
 * One subscription as Shopify reports it: [topic] and [format] are Admin API enum names (`PRODUCTS_CREATE`, `JSON`), an
 * empty [includeFields] is the full payload, and [filter] is Shopify search syntax that only matching events pass.
 */
data class WebhookSubscriptionStatus(
  val id: String,
  val topic: String,
  val uri: String,
  val includeFields: List<String>,
  val filter: String?,
  val format: String,
) {
  /** A blank filter lets every event through, as a missing one does. */
  val hasFilter: Boolean get() = !filter.isNullOrBlank()

  val isJson: Boolean get() = format == "JSON"
}
