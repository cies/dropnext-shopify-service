package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.graphql.errorLabel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


private val log = KotlinLogging.logger {}

/** Multiplied by the attempt number; a throttled bucket needs seconds, not milliseconds, to be worth asking again. */
private val RETRY_BACKOFF: Duration = 1.seconds

/**
 * Paces a run of Shopify reads for one shop against its point bucket, which the shop's live webhook traffic draws on
 * too. Before a read it waits for a refill when the last answer left the bucket under [floor]. A read that failed in a
 * way a retry can fix ([ShopifyError.isRetryable]) is asked again, up to [retries] times, after the longer of a growing
 * backoff and the refill the answer calls for.
 *
 * The one place this service retries Shopify, and safe only because every call it wraps is a read: the rule against
 * retrying Shopify exists so a mutation is never sent twice.
 *
 * One pacer per run of reads, since it remembers the latest budget between them. [pause] is injected so a test can see
 * what would have been waited without waiting.
 */
class ShopifyReadPacer(
  private val floor: Double,
  private val retries: Int,
  private val pause: suspend (Duration) -> Unit,
) {
  /** What the latest answer said was left of the bucket, or `null` while none has said. */
  var budget: ShopifyRateBudget? = null
    private set

  /** Everything this pacer has waited so far, for the run's summary line. */
  var waited: Duration = Duration.ZERO
    private set

  /** [call], after any wait the bucket calls for and again after a retryable failure; [budgetOf] reads a success. */
  suspend fun <T> read(budgetOf: (T) -> ShopifyRateBudget?, call: suspend () -> ShopifyResult<T>): ShopifyResult<T> {
    budget?.takeIf { it.isBelow(floor) }?.let { wait(it.refillTo(floor)) }
    var attempt = 0
    while (true) {
      val failure = when (val answered = call()) {
        is Success -> {
          budget = budgetOf(answered.value) ?: budget
          return answered
        }
        is Failure -> answered
      }
      val reason = failure.reason
      // A throttled answer says how empty the bucket is, which is newer than what the last success said.
      (reason as? ShopifyError.GraphqlError)?.rateBudget?.let { budget = it }
      if (!reason.isRetryable || attempt >= retries) return failure
      attempt++
      log.warn { "Shopify read failed, asking again attempt=$attempt error=${reason.errorLabel}" }
      wait(maxOf(RETRY_BACKOFF * attempt, refillAfter(reason)))
    }
  }

  /** Waiting less than this would only be refused again; without a budget, the backoff alone decides. */
  private fun refillAfter(reason: ShopifyError): Duration {
    val requestedCost = (reason as? ShopifyError.GraphqlError)?.requestedCost
    return budget?.retryAfterThrottle(floor, requestedCost) ?: Duration.ZERO
  }

  private suspend fun wait(duration: Duration) {
    if (duration <= Duration.ZERO) return
    waited += duration
    pause(duration)
  }
}
