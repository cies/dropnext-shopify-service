package dropnext.dss.lib.ktor

import dropnext.dss.contract.SyncShipmentsWithFulfillmentsResponse
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test


class DssErrorTest {

  private val emptyReport = SyncShipmentsWithFulfillmentsResponse(shipments = emptyList(), cancellations = emptyList())

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

  /** Waiting a fraction less than the bucket needs would only be throttled again. */
  @Test
  fun `a wait in whole seconds is rounded up`() {
    assert(1500.milliseconds.inWholeSecondsRoundedUp() == 2)
    assert(2.seconds.inWholeSecondsRoundedUp() == 2)
    assert(Duration.ZERO.inWholeSecondsRoundedUp() == 0)
  }

  @Test
  fun `throttled maps to 429`() {
    assert(DssError.Throttled(10.seconds).toHttpStatus() == HttpStatusCode.TooManyRequests)
  }

  @Test
  fun `internal maps to 500 with a message that says nothing`() {
    assert(DssError.Internal.toHttpStatus() == HttpStatusCode.InternalServerError)
    assert(DssError.Internal.message == "internal error")
  }

  /** A stopped sync is answered as its cause would be, so the monolith's retry decision does not change. */
  @Test
  fun `a stopped shipments sync takes the status and message of what stopped it`() {
    val stopped = DssError.ShipmentSyncStopped(DssError.UpstreamFailure("Shopify answered HTTP 503"), emptyReport)
    assert(stopped.toHttpStatus() == HttpStatusCode.BadGateway)
    assert(stopped.message == "Shopify answered HTTP 503")
    assert(DssError.ShipmentSyncStopped(DssError.NotFound("order not found"), emptyReport).toHttpStatus() == HttpStatusCode.NotFound)
    assert(DssError.ShipmentSyncStopped(DssError.Throttled(10.seconds), emptyReport).toHttpStatus() == HttpStatusCode.TooManyRequests)
  }
}
