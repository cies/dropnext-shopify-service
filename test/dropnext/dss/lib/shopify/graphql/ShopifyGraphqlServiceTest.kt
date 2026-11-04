package dropnext.dss.lib.shopify.graphql

import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test


/** Which Shopify failures a retry can fix. Shopify sends its Graphql errors with HTTP `200`, so their codes decide. */
class ShopifyGraphqlServiceTest {

  @Test
  fun `throttling and Shopify's internal errors are retryable Graphql errors`() {
    assert(ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED")).isRetryable)
    assert(ShopifyError.GraphqlError("Internal error", codes = listOf("INTERNAL_SERVER_ERROR")).isRetryable)
  }

  @Test
  fun `a Graphql error no retry can fix is not retryable`() {
    assert(!ShopifyError.GraphqlError("Query cost exceeds the maximum", codes = listOf("MAX_COST_EXCEEDED")).isRetryable)
    assert(!ShopifyError.GraphqlError("Shop inactive", codes = listOf("SHOP_INACTIVE")).isRetryable)
    // Mixed with a permanent code, an access denied stays a Graphql error, and the permanent code decides.
    assert(!ShopifyError.GraphqlError("Denied; too costly", codes = listOf("ACCESS_DENIED", "MAX_COST_EXCEEDED")).isRetryable)
  }

  /** A redelivery goes differently once the merchant approved the scope, which is the next thing asked of them. */
  @Test
  fun `a missing access scope is retryable, and only a human can make the retry go differently`() {
    val denied = ShopifyError.AccessDenied("Access denied for order field.", listOf("read_orders"))
    assert(denied.isRetryable)
    assert(denied.needsHumanAction)
  }

  /** No merchant's click grants a scope the install never asks for: only a deploy that adds it, so a retry is wasted. */
  @Test
  fun `a denied scope the install does not ask for is not retryable, and only a deploy fixes it`() {
    val denied = ShopifyError.AccessDenied("Access denied for customer field.", listOf("read_customers"))
    assert(denied.needsDeploy)
    assert(!denied.isRetryable)
    assert(denied.needsHumanAction)
  }

  @Test
  fun `a denial naming one scope the install asks for among others is the merchant's to fix`() {
    val denied = ShopifyError.AccessDenied("Access denied.", listOf("read_customers", "read_orders"))
    assert(!denied.needsDeploy)
    assert(denied.isRetryable)
  }

  /** Without a handle there is nothing to tell the two apart by, so it stays what a missing scope was. */
  @Test
  fun `a denial naming no scope is the merchant's to fix`() {
    val denied = ShopifyError.AccessDenied("Access denied for order field.", emptyList())
    assert(!denied.needsDeploy)
    assert(denied.isRetryable)
  }

  @Test
  fun `a failure needs a human exactly when no retry fixes it, or when it is a missing scope`() {
    assert(ShopifyError.TokenRejected(401).needsHumanAction)
    assert(ShopifyError.Truncated("product.variants", 100).needsHumanAction)
    assert(!ShopifyError.Network("connection reset").needsHumanAction)
    assert(!ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED")).needsHumanAction)
  }

  @Test
  fun `a missing access scope has its own label`() {
    assert(ShopifyError.AccessDenied("Access denied for order field.", emptyList()).errorLabel == "shopify_access_denied")
  }

  /** The service's own "Shopify answered something broken" errors carry no code, and are worth another attempt. */
  @Test
  fun `a Graphql error without a code is retryable`() {
    assert(ShopifyError.GraphqlError("empty response").isRetryable)
  }

  @Test
  fun `an HTTP error is retryable for throttling, timeouts and server errors only`() {
    listOf(408, 429, 500, 502, 503).forEach { status -> assert(ShopifyError.HttpError(status).isRetryable) }
    listOf(400, 402, 403, 404, 423).forEach { status -> assert(!ShopifyError.HttpError(status).isRetryable) }
  }

  @Test
  fun `a network failure is retryable, refusals and unreadable answers are not`() {
    assert(ShopifyError.Network("connection reset").isRetryable)
    assert(!ShopifyError.TokenRejected(401).isRetryable)
    assert(!ShopifyError.UserError(listOf("quantity exceeds remaining")).isRetryable)
    assert(!ShopifyError.NotFound("order 1 not found").isRetryable)
    assert(!ShopifyError.Undecodable("Unexpected JSON token").isRetryable)
    assert(!ShopifyError.Truncated("product.variants", 100).isRetryable)
    // A later walk may meet a bucket less drained.
    assert(ShopifyError.TimedOut("reading the shop's catalog", 140.seconds).isRetryable)
  }

  @Test
  fun `a timed-out walk says what did not finish and within how long`() {
    val timedOut = ShopifyError.TimedOut("reading the shop's catalog", 140.seconds)
    assert(timedOut.message == "reading the shop's catalog did not finish within 2m 20s")
    assert(timedOut.errorLabel == "shopify_timed_out")
  }

  /** The message is what an operator acts on, so it has to name both the connection and the ceiling it hit. */
  @Test
  fun `a truncated connection says which one it was and how far the query looked`() {
    val truncated = ShopifyError.Truncated("product.variants", 100)
    assert(truncated.message == "Shopify has more product.variants than the 100 this service loads")
  }
}
