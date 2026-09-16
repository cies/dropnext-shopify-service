package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.domain.RequestValidation
import org.junit.jupiter.api.Test


class ValidateTrackingUpdateRequestTest {

  @Test
  fun `accepts valid tracking update request`() {
    assert(validateTrackingUpdateRequest(trackingUpdate()) is RequestValidation.Valid)
  }

  @Test
  fun `rejects non-positive shopify_order_id`() {
    assert(validateTrackingUpdateRequest(trackingUpdate(shopifyOrderId = -1L)) is RequestValidation.Invalid)
  }

  @Test
  fun `rejects a blank tracking number and a blank status`() {
    assert(validateTrackingUpdateRequest(trackingUpdate(trackingNumber = "")) is RequestValidation.Invalid)
    assert(validateTrackingUpdateRequest(trackingUpdate(status = "")) is RequestValidation.Invalid)
  }

  @Test
  fun `accumulates every error`() {
    val result = validateTrackingUpdateRequest(trackingUpdate(shopifyOrderId = 0L, trackingNumber = "", status = ""))
    assert(result is RequestValidation.Invalid)
    assert((result as RequestValidation.Invalid).messages.size == 3)
  }

  /** What the monolith sends is `Instant.toString()`, UTC with a `Z`; an explicit offset is as good. */
  @Test
  fun `accepts a happened_at with a Z or an offset`() {
    assert(validateTrackingUpdateRequest(trackingUpdate(happenedAt = "2026-04-02T08:30:00Z")) is RequestValidation.Valid)
    assert(validateTrackingUpdateRequest(trackingUpdate(happenedAt = "2026-04-02T10:30:00+02:00")) is RequestValidation.Valid)
  }

  @Test
  fun `rejects a happened_at that is not a date and time with an offset`() {
    listOf("", "not-a-date", "2026-04-02", "2026-04-02T08:30:00").forEach { happenedAt ->
      val result = validateTrackingUpdateRequest(trackingUpdate(happenedAt = happenedAt))
      assert(result is RequestValidation.Invalid)
      assert((result as RequestValidation.Invalid).messages.single().startsWith("happened_at"))
    }
  }

  private fun trackingUpdate(
    shopifyOrderId: Long = 1001L,
    trackingNumber: String = "1Z999",
    status: String = "in_transit",
    happenedAt: String = "2026-04-02T08:30:00Z",
  ): TrackingUpdateRequest =
    TrackingUpdateRequest(
      shopifySubdomain = "acme",
      shopifyOrderId = shopifyOrderId,
      trackingNumber = trackingNumber,
      status = status,
      happenedAt = happenedAt,
      message = null,
    )
}
