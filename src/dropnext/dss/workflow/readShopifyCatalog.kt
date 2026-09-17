package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyProductId
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.domain.ShopifyVariantId
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.lib.shopify.graphql.errorLabel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull


private val log = KotlinLogging.logger {}

/**
 * How much of the shop's variant list one Shopify call asks for. A property of this service rather than of its caller,
 * so the cost of a page is tunable in one place; the ceiling Shopify allows is 250.
 */
const val CATALOG_PAGE_SIZE: Int = 250

/**
 * The point below which the walk waits for the bucket to refill instead of asking for the next page. Half is
 * deliberately generous: the other half is what the shop's live webhook traffic is drawing on while we read.
 */
const val CATALOG_BUDGET_FLOOR: Double = 0.5

/**
 * How long a whole walk may take. The monolith stops waiting for the answer after 150 seconds, and a walk that went on
 * past that would spend the shop's rate budget on an answer nobody reads — while the monolith's retry started a second
 * walk of the same shop. So the walk stops a little earlier, and says so in a way the monolith retries.
 */
val CATALOG_READ_BUDGET: Duration = 140.seconds

/**
 * A backstop on the number of pages. Fifty thousand variants is far past any shop we have, and [CATALOG_READ_BUDGET]
 * ends a walk long before it gets here; a shop this size needs a bulk operation, not a longer walk. A cursor that does
 * not advance is caught on its own, the first time it repeats.
 */
const val CATALOG_MAX_PAGES: Int = 200

/**
 * How often a page is waited out and asked for again before the whole walk fails. Throttling is the case this exists
 * for, but any failure Shopify says a retry can fix qualifies ([ShopifyError.isRetryable]): the page is a read with the
 * same cursor, so asking again is harmless, and a walk of two hundred pages should not be lost to one dropped
 * connection.
 */
const val CATALOG_PAGE_RETRIES: Int = 3

/** The shop's whole catalog, grouped by product, and what the last page said was left of the bucket. */
data class ShopCatalog(
  val products: List<ShopCatalogEntry>,
  val productVariantCount: Int,
  val rateBudget: ShopifyRateBudget?,
)

/** One product of the catalog with every variant Shopify has under it. */
data class ShopCatalogEntry(
  val productId: ShopifyProductId,
  val productVariantIds: List<ShopifyVariantId>,
)

/**
 * Walks the shop's variants to the end and groups them under their products.
 *
 * **Complete or failed.** There is no partial answer and no flag saying the walk stopped early, because the monolith's
 * diff soft-deletes every variant this does not mention: a half-read catalog treated as a whole one would empty a
 * store. That is also why there is no cursor in the answer — a cursor is an invitation to act on half a catalog.
 *
 * **It paces itself.** Two hundred pages back to back would empty a Standard plan's bucket and take the shop's order
 * webhooks down with it, so the walk goes through a [ShopifyReadPacer]: it waits for a refill when the bucket drops
 * under [budgetFloor], and asks again for a page that failed in a way a retry can fix.
 *
 * **It is bounded as a whole**, by [deadline], not only page by page. [pause] is injected so a test can see what the
 * walk would have waited without waiting.
 *
 * The walk logs its outcome once, successful or not, so a handler answering its failure does not log it again.
 */
suspend fun readShopifyCatalog(
  shopify: ShopifyGraphqlService,
  pageSize: Int = CATALOG_PAGE_SIZE,
  maxPages: Int = CATALOG_MAX_PAGES,
  budgetFloor: Double = CATALOG_BUDGET_FLOOR,
  pageRetries: Int = CATALOG_PAGE_RETRIES,
  deadline: Duration = CATALOG_READ_BUDGET,
  pause: suspend (Duration) -> Unit = { delay(it) },
): ShopifyResult<ShopCatalog> {
  val walk = CatalogWalk(shopify, pageSize, maxPages, ShopifyReadPacer(budgetFloor, pageRetries, pause))
  val result = withTimeoutOrNull(deadline) { walk.run() }
    ?: Failure(ShopifyError.TimedOut("reading the shop's catalog", deadline))

  when (result) {
    is Success -> log.info {
      "Catalog read done products=${result.value.products.size} variants=${result.value.productVariantCount} " +
        "pages=${walk.pages} waited_ms=${walk.waited.inWholeMilliseconds}"
    }
    // A failure a retry cannot fix needs a human: a revoked token, a catalog too large for this shape.
    is Failure -> if (result.reason.isRetryable) {
      log.warn { walk.failureLine(result.reason) }
    } else {
      log.error { walk.failureLine(result.reason) }
    }
  }
  return result
}

/** The state of one walk, kept outside the deadline so what it got through is still known when the deadline ends it. */
private class CatalogWalk(
  private val shopify: ShopifyGraphqlService,
  private val pageSize: Int,
  private val maxPages: Int,
  private val pacer: ShopifyReadPacer,
) {
  // Insertion-ordered so a product whose variants straddle a page boundary still lands under one entry, once.
  private val grouped = LinkedHashMap<ShopifyProductId, MutableList<ShopifyVariantId>>()

  var pages = 0
    private set

  val waited: Duration get() = pacer.waited

  suspend fun run(): ShopifyResult<ShopCatalog> {
    var cursor: String? = null
    while (true) {
      if (pages >= maxPages) return Failure(ShopifyError.Truncated("productVariants", maxPages * pageSize))

      val after = cursor
      val answered = pacer.read({ it.rateBudget }) { shopify.productVariantIdsPage(first = pageSize, after = after) }
      val page = when (answered) {
        is Failure -> return answered
        is Success -> answered.value
      }
      pages++
      page.entries.forEach { entry ->
        grouped.getOrPut(entry.productId) { mutableListOf() }.add(entry.productVariantId)
      }

      val next = page.nextCursor ?: break
      // Shopify handing back the cursor it was just given would loop until the page backstop, reading the same page
      // over and over; nothing in its answer changes on a retry of the page, but a fresh walk may go differently.
      if (next == cursor) return Failure(ShopifyError.GraphqlError("Shopify answered the cursor it was given as the next one"))
      cursor = next
    }

    return Success(
      ShopCatalog(
        products = grouped.map { (productId, variantIds) -> ShopCatalogEntry(productId, variantIds) },
        productVariantCount = grouped.values.sumOf { it.size },
        rateBudget = pacer.budget,
      )
    )
  }

  fun failureLine(reason: ShopifyError): String =
    "Catalog read failed pages=$pages waited_ms=${waited.inWholeMilliseconds} error=${reason.errorLabel}: ${reason.message}"
}
