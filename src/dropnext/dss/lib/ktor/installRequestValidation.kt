package dropnext.dss.lib.ktor

import dropnext.dss.contract.SyncShipmentsWithFulfillmentsRequest
import dropnext.dss.contract.TrackingUpdateRequest
import dropnext.dss.contract.UpdateStoreApiKeyRequest
import dropnext.dss.domain.RequestValidation as DomainRequestValidation
import dropnext.dss.domain.fulfillment.validateSyncShipmentsRequest
import dropnext.dss.domain.fulfillment.validateTrackingUpdateRequest
import dropnext.dss.domain.validateUpdateStoreApiKeyRequest
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult


/**
 * Runs the domain validators on every decoded request body, so a handler only ever sees a valid one. A
 * failure is a `RequestValidationException`, which `installStatusPages` answers with the 400 of
 * [DssError.InvalidRequest]; the validators themselves stay Ktor-free in `domain/`.
 */
fun Application.installRequestValidation() {
  install(RequestValidation) {
    validate<SyncShipmentsWithFulfillmentsRequest> { validateSyncShipmentsRequest(it).toValidationResult() }
    validate<TrackingUpdateRequest> { validateTrackingUpdateRequest(it).toValidationResult() }
    validate<UpdateStoreApiKeyRequest> { validateUpdateStoreApiKeyRequest(it).toValidationResult() }
  }
}

private fun DomainRequestValidation.toValidationResult(): ValidationResult = when (this) {
  DomainRequestValidation.Valid -> ValidationResult.Valid
  is DomainRequestValidation.Invalid -> ValidationResult.Invalid(messages)
}
