package dropnext.dss.lib.ktor

import io.ktor.http.HttpStatusCode
import kotlin.test.Test

class DssErrorTest {

  @Test
  fun `invalid request maps to 400`() {
    assert(DssError.InvalidRequest("bad").toHttpStatus() == HttpStatusCode.BadRequest)
  }

  @Test
  fun `missing parameter renders structured message`() {
    val e = DssError.MissingParameter("shop")
    assert(e.message == "Missing shop")
    assert(e.toHttpStatus() == HttpStatusCode.BadRequest)
  }

  @Test
  fun `invalid parameter with detail interpolates detail`() {
    val e = DssError.InvalidParameter("shopify_subdomain", "not a valid host")
    assert(e.message == "Invalid shopify_subdomain: not a valid host")
    assert(e.toHttpStatus() == HttpStatusCode.BadRequest)
  }

  @Test
  fun `invalid parameter without detail uses generic message`() {
    val e = DssError.InvalidParameter("shopify_subdomain")
    assert(e.message == "Invalid shopify_subdomain")
  }

  @Test
  fun `missing shopify admin token maps to 401`() {
    val e = DssError.MissingShopifyAdminToken
    assert(e.toHttpStatus() == HttpStatusCode.Unauthorized)
    assert("PUT /stores/api-key" in e.message)
  }

  @Test
  fun `rejected shopify admin token maps to 401 and says to reinstall`() {
    val e = DssError.ShopifyAdminTokenRejected(401)
    assert(e.toHttpStatus() == HttpStatusCode.Unauthorized)
    assert("HTTP 401" in e.message)
    assert("reinstall" in e.message)
  }

  /** Unlike a missing token, a retry can fix a lookup the monolith did not answer, and the monolith retries a 5xx. */
  @Test
  fun `an unavailable shopify admin token maps to 502`() {
    assert(DssError.ShopifyAdminTokenUnavailable.toHttpStatus() == HttpStatusCode.BadGateway)
  }

  @Test
  fun `invalid signature maps to 403`() {

    assert(DssError.InvalidSignature("Invalid HMAC").toHttpStatus() == HttpStatusCode.Forbidden)
  }

  @Test
  fun `not found maps to 404`() {
    assert(DssError.NotFound("order 1 not found").toHttpStatus() == HttpStatusCode.NotFound)
  }

  @Test
  fun `payload too large maps to 413`() {
    assert(DssError.PayloadTooLarge.toHttpStatus() == HttpStatusCode.PayloadTooLarge)
  }

  @Test
  fun `upstream failure maps to 502`() {
    assert(DssError.UpstreamFailure("network down").toHttpStatus() == HttpStatusCode.BadGateway)
  }

  @Test
  fun `internal maps to 500 with a message that says nothing`() {
    assert(DssError.Internal.toHttpStatus() == HttpStatusCode.InternalServerError)
    assert(DssError.Internal.message == "internal error")
  }
}
