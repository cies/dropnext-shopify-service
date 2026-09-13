package dropnext.dss.workflow

import dropnext.dss.lib.monolith.MonolithError
import dropnext.dss.lib.monolith.MonolithErrorBody
import dropnext.dss.lib.shopify.graphql.ShopifyError
import kotlin.test.Test


/** Which outcomes Shopify is asked to redeliver: the webhook handler's `502` or `200` is read off this. */
class WebhookMirrorOutcomeTest {

  @Test
  fun `a Shopify failure is transient exactly when Shopify's error is retryable`() {
    assert(WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED"))).isTransient)
    assert(!WebhookMirrorOutcome.ShopifyFailed(ShopifyError.GraphqlError("Access denied", codes = listOf("ACCESS_DENIED"))).isTransient)
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
