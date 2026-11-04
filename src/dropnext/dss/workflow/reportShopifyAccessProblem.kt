package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.contract.ShopifyAccessProblemKind
import dropnext.dss.contract.ShopifyAccessProblemRequest
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.monolith.MonolithService
import dropnext.dss.lib.monolith.logMonolithFailure
import dropnext.dss.lib.shopify.graphql.ShopifyError
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull


private val log = KotlinLogging.logger {}

/**
 * How long a report may hold up an answer the monolith is waiting for: one attempt of the monolith client. The
 * monolith's own `401` or `403` and its next check carry the same fact, so a report cut short loses nothing for good.
 */
val ACCESS_PROBLEM_REPORT_BUDGET: Duration = 5.seconds

/**
 * Tells the monolith that Shopify refused the shop's token or a scope of its grant, the moment the DSS sees it, so the
 * monolith can flag the store and send the retailer the reconnect link. Every other failure is no news and sends nothing,
 * and so is a scope the install does not ask for ([ShopifyError.AccessDenied.needsDeploy]): no reconnect grants it, so a
 * notification would only send the retailer round a loop. That one is an error line for whoever deploys instead.
 *
 * The report names the token Shopify refused (the error's fingerprint), so the monolith can tell a report about a token
 * a reinstall has since replaced from news, whatever either clock says.
 *
 * [observedDuring] names the operation for a human (`orders/create webhook`, `tracking-update`). The report runs
 * [within] what its caller has left, and is skipped once nothing is: it never changes the caller's answer, so a
 * failed or skipped report is a log line and nothing more.
 */
suspend fun reportShopifyAccessProblem(
  monolith: MonolithService,
  shop: ShopDomain,
  error: ShopifyError,
  observedDuring: String,
  observedAt: Instant,
  within: Duration = ACCESS_PROBLEM_REPORT_BUDGET,
) {
  val request = when (error) {
    is ShopifyError.TokenRejected -> accessProblem(
      shop, ShopifyAccessProblemKind.TOKEN_REJECTED, emptyList(), observedDuring, observedAt, error.tokenFingerprint,
    )
    is ShopifyError.AccessDenied -> {
      if (error.needsDeploy) {
        val scopes = error.requiredScopeHandles.joinToString(",")
        log.error {
          "Shopify access problem not reported reason=scope_not_requested required_scopes=$scopes " +
            "observed_during=\"$observedDuring\"; a deploy has to add the scope to the install"
        }
        return
      }
      accessProblem(
        shop, ShopifyAccessProblemKind.ACCESS_SCOPE_MISSING, error.requiredScopeHandles, observedDuring, observedAt,
        error.tokenFingerprint,
      )
    }
    else -> return
  }
  val detail = "kind=${request.kind} observed_during=\"$observedDuring\""
  val answered = if (within.isPositive()) withTimeoutOrNull(within) { monolith.postShopifyAccessProblem(request) } else null
  when (answered) {
    null -> log.warn { "Shopify access problem not reported reason=budget_exhausted $detail" }
    is Success -> log.info { "Shopify access problem reported $detail first_report=${answered.value.firstReport}" }
    is Failure -> logMonolithFailure("postShopifyAccessProblem", answered.reason, detail)
  }
}

private fun accessProblem(
  shop: ShopDomain,
  kind: ShopifyAccessProblemKind,
  missingScopeHandles: List<String>,
  observedDuring: String,
  observedAt: Instant,
  tokenFingerprint: String?,
) = ShopifyAccessProblemRequest(
  shopifySubdomain = shop.subdomainOnly,
  kind = kind,
  missingAccessScopes = missingScopeHandles,
  observedDuring = observedDuring,
  observedAt = observedAt.toString(),
  tokenFingerprint = tokenFingerprint,
)
