package dropnext.dss.workflow

import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithErrorBody
import dropnext.dss.lib.shopify.graphql.ShopifyError
import org.junit.jupiter.api.Test


/** Which outcomes Shopify is asked to redeliver: the webhook handler's `502` or `200` is read off this. */
class WebhookMirrorOutcomeTest {

  @Test
  fun `a Shopify failure is transient exactly when Shopify's error is retryable`() {
    assert(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))).isTransient)
    assert(!WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("Too costly", codes = listOf("MAX_COST_EXCEEDED"))).isTransient)
  }

  /** Shopify's four-hour redelivery window covers the merchant approving the missing scope. */
  @Test
  fun `a missing access scope is transient`() {
    val denied = ShopifyError.AccessDenied("Access denied for order field.", listOf("read_orders"))
    assert(WebhookMirrorOutcome.ShopifyFailed(denied).isTransient)
  }

  /** A redelivery cannot go differently before a deploy adds the scope, and enough failed ones cost the subscription. */
  @Test
  fun `a denied scope the install does not ask for is not transient`() {
    val denied = ShopifyError.AccessDenied("Access denied for customer field.", listOf("read_customers"))
    assert(!WebhookMirrorOutcome.ShopifyFailed(denied).isTransient)
  }

  /** A key rotation in progress: once both sides hold the same key, a redelivery goes through. */
  @Test
  fun `a monolith that refuses our key is transient`() {
    val unauthorized = MonolithError.Rejected(401, "no", MonolithErrorBody(message = null, code = null, monolithTraceId = null))
    val forbidden = MonolithError.Rejected(403, "no", MonolithErrorBody(message = null, code = null, monolithTraceId = null))
    assert(WebhookMirrorOutcome.MonolithFailed(unauthorized).isTransient)
    assert(WebhookMirrorOutcome.MonolithFailed(forbidden).isTransient)
  }

  /** A `200` whose body is not the contract's DTO is the contract having drifted, which the next attempt reads the same. */
  @Test
  fun `a monolith that did not answer or answered a 5xx is transient, a refusal or an unreadable success is not`() {
    val refused = MonolithError.Rejected(400, "bad", MonolithErrorBody(message = "bad", code = null, monolithTraceId = null))
    val down = MonolithError.Rejected(503, "down", MonolithErrorBody(message = null, code = null, monolithTraceId = null))
    assert(WebhookMirrorOutcome.MonolithFailed(MonolithError.Transport("connection refused")).isTransient)
    assert(WebhookMirrorOutcome.MonolithFailed(down).isTransient)
    assert(!WebhookMirrorOutcome.MonolithFailed(refused).isTransient)
    assert(!WebhookMirrorOutcome.MonolithFailed(MonolithError.Undecodable(200, "not the expected JSON")).isTransient)
  }

  @Test
  fun `a token the monolith could not be asked for, a blown time budget and a full service are transient`() {
    assert(WebhookMirrorOutcome.TokenUnavailable.isTransient)
    assert(WebhookMirrorOutcome.TimedOut.isTransient)
    assert(WebhookMirrorOutcome.Overloaded.isTransient)
  }
}
