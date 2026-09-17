package dropnext.dss.handler

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.oauth.OAuthError
import dropnext.dss.workflow.CATALOG_BUDGET_FLOOR
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * Maps a Shopify failure onto the HTTP answer: what Shopify refused is the caller's problem, a
 * refused token is ours to fix by reinstalling, what failed on the way is upstream.
 *
 * An upstream failure is a `502` whether or not a retry can fix it ([ShopifyError.isRetryable]). The monolith retries a
 * `5xx` into its dead-letter queue, where the message waits to be replayed once the cause is fixed, and drops a `4xx` for
 * good; a missing scope or a query Shopify will not run is not the caller's fault, so it gets the answer that keeps it.
 *
 * The one exception is throttling, a `429` that says how long to wait: the monolith retries it as well, and waiting the
 * shop's own refill time is what keeps a retry from being throttled again.
 */
fun ShopifyError.toDssError(): DssError = when (this) {
  is ShopifyError.NotFound -> DssError.NotFound(message)
  is ShopifyError.UserError -> DssError.InvalidRequest(message)
  is ShopifyError.TokenRejected -> DssError.ShopifyAdminTokenRejected(httpStatus)
  is ShopifyError.GraphqlError ->
    if (THROTTLED_CODE in codes) DssError.Throttled(retryAfterFor(rateBudget)) else DssError.UpstreamFailure(message)
  is ShopifyError.HttpError ->
    if (httpStatus == 429) DssError.Throttled(DEFAULT_THROTTLED_RETRY_AFTER) else DssError.UpstreamFailure(message)
  is ShopifyError.Network -> DssError.UpstreamFailure(message)
  // The decoder's complaint quotes Shopify's body, which can carry customer data: the log has it, the caller gets the gist.
  is ShopifyError.Undecodable -> DssError.UpstreamFailure("Shopify's answer could not be read")
  // Nothing upstream failed and nothing in the request is wrong: this service cannot load the whole shop's data yet.
  is ShopifyError.Truncated -> DssError.Unsupported
  // A `5xx`, so the monolith retries it: a later attempt may find the shop's rate budget less drained.
  is ShopifyError.TimedOut -> DssError.UpstreamFailure(message)
}

private const val THROTTLED_CODE = "THROTTLED"

/**
 * What a throttled answer tells its caller to wait when Shopify said nothing about the bucket. Ten seconds refills half
 * of a 1,000-point bucket at the slowest documented restore rate, so a caller that honors it is unlikely to be
 * throttled again straight away.
 */
private val DEFAULT_THROTTLED_RETRY_AFTER: Duration = 10.seconds

/**
 * How long Shopify needs to refill the shop's bucket to the share every read here leaves free for the shop's live
 * traffic, never less than a second: a retry at once would only be throttled again.
 */
private fun retryAfterFor(budget: ShopifyRateBudget?): Duration =
  budget?.refillTo(CATALOG_BUDGET_FLOOR)?.coerceAtLeast(1.seconds) ?: DEFAULT_THROTTLED_RETRY_AFTER

/**
 * A merchant's browser reads these. A code Shopify refused is theirs to redo, so the answer says to start the
 * install again; Shopify not answering is upstream, and the page stays as terse as before.
 */
fun OAuthError.toDssError(): DssError = when (this) {
  is OAuthError.CodeRejected ->
    DssError.InvalidRequest("OAuth failed: Shopify refused the authorization code (HTTP $httpStatus); start the install again")
  is OAuthError.Transport -> DssError.UpstreamFailure("OAuth failed: could not exchange authorization code")
}

/**
 * A token the monolith did not persist is cached here regardless, so the answer has to say that the
 * monolith does not have it. A store the monolith does not know is the caller's to fix; anything else
 * is the monolith's, and a retry of the same request is safe. The monolith's own message stays in the log.
 */
fun MonolithPersistOutcome.Failed.toDssError(): DssError = when (httpStatus) {
  404 -> DssError.NotFound("the monolith knows no store for this shop; the token is cached in memory only")
  null -> DssError.UpstreamFailure("the monolith did not answer; the token is cached in memory only")
  else -> DssError.UpstreamFailure("the monolith answered HTTP $httpStatus; the token is cached in memory only")
}

