package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.domain.RequestValidation
import dropnext.dss.domain.toRequestValidation
import dropnext.dss.domain.validateShopifyOrderId
import java.time.OffsetDateTime


fun validateTrackingUpdateRequest(request: TrackingUpdateRequest): RequestValidation {
  val errors = mutableListOf<String>()
  errors += validateShopifyOrderId(request.shopifyOrderId)
  if (request.trackingNumber.isBlank()) errors += "tracking_number is required"
  if (request.status.isBlank()) errors += "status is required"
  errors += validateHappenedAt(request.happenedAt)
  return errors.toRequestValidation()
}

/**
 * Shopify refuses a `happenedAt` it cannot read as a `DateTime` with a top-level Graphql error, which is answered as an
 * upstream failure the monolith keeps retrying; refused here, it is the `400` it really is. The offset is required
 * because a local time names a different instant in every time zone.
 */
private fun validateHappenedAt(happenedAt: String): List<String> =
  if (runCatching { OffsetDateTime.parse(happenedAt) }.isSuccess) emptyList()
  else listOf("happened_at must be an ISO 8601 date and time with an offset, such as 2026-04-02T08:30:00Z")
