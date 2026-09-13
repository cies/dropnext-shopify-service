package dropnext.dss.lib.ktor

import dropnext.dss.contract.ApiError
import dropnext.dss.lib.slf4j.currentTraceId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText


/**
 * Outbound error taxonomy for DSS handlers. One sealed hierarchy means handlers describe the
 * failure shape and the central [respondError] / [respondTextError] helpers decide the HTTP
 * status and body — no scattered `respond(BadRequest, …)` calls.
 *
 * JSON endpoints (the OpenAPI DSS contract, served by `MonolithWebhookHandlers`) use [respondError],
 * which emits an [ApiError]. The HTML OAuth flow uses [respondTextError] so error pages stay
 * plain text. The request's trace id travels in the `X-Trace-Id` response header on both.
 */
sealed interface DssError {
  val message: String

  /** 400 — generic client-side validation / payload problem. */
  data class InvalidRequest(override val message: String) : DssError

  /** 400 — required query parameter is missing. */
  data class MissingParameter(val name: String) : DssError {
    override val message: String = "Missing $name"
  }

  /** 400 — supplied query parameter is structurally invalid. */
  data class InvalidParameter(val name: String, val detail: String? = null) : DssError {
    override val message: String = if (detail == null) "Invalid $name" else "Invalid $name: $detail"
  }

  /** 401 — no Shopify Admin token resolvable for the shop in question. */
  data object MissingShopifyAdminToken : DssError {
    override val message: String =
      "missing Shopify Admin token: configure DSS_SHOP_ACCESS_TOKENS, complete OAuth install, " +
        "or persist a token via PUT /stores/api-key"
  }

  /**
   * 401 — Shopify refused the Admin token we hold for the shop. The same status as
   * [MissingShopifyAdminToken] because the remedy is the same: the monolith cannot retry its way
   * out of it, a human has to reinstall the app.
   */
  data class ShopifyAdminTokenRejected(val httpStatus: Int) : DssError {
    override val message: String =
      "Shopify rejected the shop's Admin token (HTTP $httpStatus): the app was uninstalled or the token revoked, reinstall it"
  }

  /**
   * 502 — the shop's token is not in memory and the monolith, which holds every token, could not be asked. Kept apart
   * from [MissingShopifyAdminToken] because it asks for the opposite: that one no retry can fix, this one a retry may.
   */
  data object ShopifyAdminTokenUnavailable : DssError {
    override val message: String = "could not look up the shop's Shopify Admin token at the monolith"
  }

  /** 403 — HMAC or signed-state verification failed. */
  data class InvalidSignature(override val message: String) : DssError


  /** 404 — a referenced resource (order, fulfillment, …) does not exist. */
  data class NotFound(override val message: String) : DssError

  /** 413 — the body exceeds `MAX_REQUEST_BODY_BYTES`; the read was aborted before it was buffered. */
  data object PayloadTooLarge : DssError {
    override val message: String = "request body too large"
  }

  /** 502 — upstream call (Shopify Admin, monolith) failed, and the request cannot continue. */
  data class UpstreamFailure(override val message: String) : DssError

  /** 500 — a bug. The message is deliberately generic; the details are in the log under the trace id. */
  data object Internal : DssError {
    override val message: String = "internal error"
  }
}

fun DssError.toHttpStatus(): HttpStatusCode = when (this) {
  is DssError.InvalidRequest,
  is DssError.MissingParameter,
  is DssError.InvalidParameter,
    -> HttpStatusCode.BadRequest

  is DssError.MissingShopifyAdminToken,
  is DssError.ShopifyAdminTokenRejected,
    -> HttpStatusCode.Unauthorized


  is DssError.InvalidSignature -> HttpStatusCode.Forbidden

  is DssError.NotFound -> HttpStatusCode.NotFound

  is DssError.PayloadTooLarge -> HttpStatusCode.PayloadTooLarge

  is DssError.UpstreamFailure,
  is DssError.ShopifyAdminTokenUnavailable,
    -> HttpStatusCode.BadGateway

  is DssError.Internal -> HttpStatusCode.InternalServerError
}

/**
 * JSON-error responder for the OpenAPI DSS contract: the monolith's [ApiError] shape, so a client
 * of either service reads one error body. The trace id is ours, from the MDC, so the monolith can
 * quote it back when it logs a refused call; there is no error code vocabulary on this side yet.
 */
suspend fun ApplicationCall.respondError(e: DssError) {
  respond(e.toHttpStatus(), ApiError(error = e.message, traceId = currentTraceId()))
}

/** Plain-text error responder, used by OAuth/install routes that render HTML on success. */
suspend fun ApplicationCall.respondTextError(e: DssError) {
  respondText(e.message, status = e.toHttpStatus())
}
