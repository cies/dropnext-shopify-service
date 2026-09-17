package dropnext.dss.handler

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.ktor.toHttpStatus
import dropnext.dss.lib.shopify.graphql.ShopifyError
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test


class ToDssErrorTest {

  @Test
  fun `what Shopify refused is the caller's problem`() {
    assert(ShopifyError.NotFound("missing").toDssError() == DssError.NotFound("missing"))
    assert(ShopifyError.UserError(listOf("qty", "tracking")).toDssError() == DssError.InvalidRequest("qty; tracking"))
  }

  @Test
  fun `what failed on the way is an upstream failure carrying Shopify's message`() {
    // No THROTTLED code: Shopify said something went wrong, not that the shop is out of budget.
    assert(ShopifyError.GraphqlError("throttled").toDssError() == DssError.UpstreamFailure("throttled"))
    val internal = ShopifyError.GraphqlError("oops", codes = listOf("INTERNAL_SERVER_ERROR"))
    assert(internal.toDssError() == DssError.UpstreamFailure("oops"))
    assert(ShopifyError.HttpError(503).toDssError() == DssError.UpstreamFailure("Shopify answered HTTP 503"))
    assert(ShopifyError.Network("down").toDssError() == DssError.UpstreamFailure("down"))
  }

  /** The decoder's complaint quotes Shopify's body, which can carry customer data. */
  @Test
  fun `an unreadable answer reaches the caller without what the decoder quoted`() {
    val mapped = ShopifyError.Undecodable("JSON input: {\"email\":\"jane@example.com\"}").toDssError()
    assert(mapped == DssError.UpstreamFailure("Shopify's answer could not be read"))
  }

  /** A retry cannot fix a revoked token, so it must not be filed under "upstream" where the monolith would retry it. */
  @Test
  fun `a rejected token is the shop's install problem`() {
    assert(ShopifyError.TokenRejected(401).toDssError() == DssError.ShopifyAdminTokenRejected(401))
  }

  /**
   * Nothing upstream failed and nothing in the request is wrong, so neither a `502` nor a `4xx` reads true. The
   * monolith retries a `5xx` into its dead-letter queue, which is where a shop this service cannot serve belongs.
   */
  @Test
  fun `a truncated connection is unsupported, not an upstream failure`() {
    val mapped = ShopifyError.Truncated("order.lineItems", 100).toDssError()
    assert(mapped == DssError.Unsupported)
    assert(mapped.toHttpStatus() == HttpStatusCode.InternalServerError)
    // The connection and the ceiling belong in the log, never in an answer a caller reads.
    assert(mapped.message == "internal error")
  }

  // ---------- throttling ----------

  private val emptyBucket = ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = 0.0, restoreRate = 50.0)

  /** The wait is the shop's own refill to half its bucket: 500 points at 50 a second. */
  @Test
  fun `a throttled answer waits for the refill Shopify reported`() {
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"), rateBudget = emptyBucket)

    assert(throttled.toDssError() == DssError.Throttled(10.seconds))
    assert(throttled.toDssError().toHttpStatus() == HttpStatusCode.TooManyRequests)
  }

  @Test
  fun `a throttled answer that reported no bucket waits ten seconds`() {
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))

    assert(throttled.toDssError() == DssError.Throttled(10.seconds))
  }

  /** A bucket that is already refilled still gets a second: a retry at once would only be throttled again. */
  @Test
  fun `a throttled answer never waits less than a second`() {
    val refilled = emptyBucket.copy(currentlyAvailable = 1000.0)
    val throttled = ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"), rateBudget = refilled)

    assert(throttled.toDssError() == DssError.Throttled(1.seconds))
  }

  @Test
  fun `Shopify's own 429 is throttling too`() {
    assert(ShopifyError.HttpError(429).toDssError() == DssError.Throttled(10.seconds))
  }

  /** A `5xx` the monolith retries: the next attempt may find the shop's bucket less drained. */
  @Test
  fun `a timed-out walk is an upstream failure naming what timed out`() {
    val mapped = ShopifyError.TimedOut("reading the shop's catalog", 140.seconds).toDssError()
    assert(mapped == DssError.UpstreamFailure("reading the shop's catalog did not finish within 2m 20s"))
    assert(mapped.toHttpStatus() == HttpStatusCode.BadGateway)
  }

  // ---------- a token the monolith did not persist ----------

  /** The caller has to fix a store the monolith does not know; a retry of the same request cannot. */
  @Test
  fun `a store the monolith does not know is a not found`() {
    val mapped = MonolithPersistOutcome.Failed(httpStatus = 404, detail = "no store").toDssError()
    assert(mapped == DssError.NotFound("the monolith knows no store for this shop; the token is cached in memory only"))
  }

  /** No status is how a transport failure arrives; the answer must still say the token lives in memory only. */
  @Test
  fun `a monolith that did not answer is an upstream failure`() {
    val mapped = MonolithPersistOutcome.Failed(httpStatus = null, detail = "connection refused").toDssError()
    assert(mapped == DssError.UpstreamFailure("the monolith did not answer; the token is cached in memory only"))
  }

  /** The monolith's own message stays in the log: the answer names the status and nothing it said. */
  @Test
  fun `any other monolith status is an upstream failure naming the status`() {
    val mapped = MonolithPersistOutcome.Failed(httpStatus = 500, detail = "boom").toDssError()
    assert(mapped == DssError.UpstreamFailure("the monolith answered HTTP 500; the token is cached in memory only"))
  }
}
