package dropnext.dss.lib.shopify.graphql

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyQueryCost
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource


private val log = KotlinLogging.logger {}

/**
 * Shopify refuses a single query whose requested cost is over 1,000 points, for every shop and on every call.
 * Two thirds of that is where a warning still leaves room to change the query before anything is refused.
 */
const val QUERY_COST_WARNING_THRESHOLD: Int = 750

/** How often the cost of one operation for one shop is logged. A bulk edit would otherwise double its own log volume. */
val QUERY_COST_SAMPLE_INTERVAL: Duration = 1.minutes

/**
 * A bound on what the reporter remembers. Operations are few, but shops are keys too, and a process that served many
 * shops should not grow the maps without end. Past it, costs are still warned about but no longer sampled.
 */
private const val MAX_SAMPLED_KEYS = 2_000

/**
 * Logs what Shopify says our queries cost. One per process, shared by every shop's service, because what it remembers
 * has to outlive the per-request services.
 *
 * - **A warning, once per operation**, when the requested cost is over [QUERY_COST_WARNING_THRESHOLD]. The requested cost
 *   depends only on the query, so the first call says everything, and repeating it would bury it.
 * - **A sample, at most once per [QUERY_COST_SAMPLE_INTERVAL] per shop and operation**, at `info`: both costs and what is
 *   left of the shop's bucket. That series shows how far a burst drains a bucket and how long the refill takes, which
 *   is what the page sizes have to be judged on. Per shop, because the bucket is.
 *
 * Reading the cost never changes an answer; this only logs.
 */
class ShopifyQueryCostReporter(
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val warnedOperations = ConcurrentHashMap.newKeySet<String>()
  private val lastSampled = ConcurrentHashMap<String, TimeMark>()

  fun report(shop: ShopDomain, operation: String, cost: ShopifyQueryCost) {
    val requested = cost.requested
    if (requested != null && requested > QUERY_COST_WARNING_THRESHOLD && warnedOperations.add(operation)) {
      log.warn {
        "Shopify query cost is near the cap operation=$operation requested=$requested actual=${cost.actual} " +
          "cap=$SHOPIFY_QUERY_COST_CAP"
      }
    }
    if (isSampleDue("${shop.normalizedShopifyHost} $operation")) {
      val budget = cost.budget
      log.info {
        "Shopify query cost operation=$operation requested=$requested actual=${cost.actual} " +
          "available=${budget?.currentlyAvailable} maximum=${budget?.maximumAvailable} restore_rate=${budget?.restoreRate}"
      }
    }
  }

  private fun isSampleDue(key: String): Boolean {
    val now = timeSource.markNow()
    val previous = lastSampled[key]
    if (previous == null) {
      if (lastSampled.size >= MAX_SAMPLED_KEYS) return false
      return lastSampled.putIfAbsent(key, now) == null
    }
    if (previous.elapsedNow() < QUERY_COST_SAMPLE_INTERVAL) return false
    // Only the caller that replaces the mark it read logs; a concurrent one lost the race and stays quiet.
    return lastSampled.replace(key, previous, now)
  }
}

/** Shopify's own limit on the requested cost of one query, whatever the shop's plan. */
const val SHOPIFY_QUERY_COST_CAP: Int = 1_000
