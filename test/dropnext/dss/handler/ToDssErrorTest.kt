package dropnext.dss.handler

import dropnext.dss.domain.MonolithPersistOutcome
import dropnext.dss.lib.ktor.DssError
import dropnext.dss.lib.shopify.graphql.ShopifyError
import org.junit.jupiter.api.Test


class ToDssErrorTest {

  @Test
  fun `what Shopify refused is the caller's problem`() {
    assert(ShopifyError.NotFound("missing").toDssError() == DssError.NotFound("missing"))
    assert(ShopifyError.UserError(listOf("qty", "tracking")).toDssError() == DssError.InvalidRequest("qty; tracking"))
  }

  @Test
  fun `what failed on the way is an upstream failure carrying Shopify's message`() {
    assert(ShopifyError.GraphqlError("throttled").toDssError() == DssError.UpstreamFailure("throttled"))
    assert(ShopifyError.HttpError(429).toDssError() == DssError.UpstreamFailure("Shopify answered HTTP 429"))
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
