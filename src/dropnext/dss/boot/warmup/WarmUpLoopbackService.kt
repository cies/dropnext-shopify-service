package dropnext.dss.boot.warmup

import dropnext.dss.domain.ShopDomain


/**
 * The two requests the warm-up sends the service itself, so the inbound pipeline (the parser, the plugins, the bearer
 * provider, the HMAC, the JSON answer) runs once end to end before the first real delivery does. Each answers what
 * came back, or `null` when no answer came; the caller decides what an answer means.
 *
 * Production wires [HttpWarmUpLoopbackService] (plain HTTP to `127.0.0.1`); tests wire `FakeWarmUpLoopbackService`.
 */
interface WarmUpLoopbackService {
  /**
   * `GET /stores/shopify-status?shopify_subdomain=` with the monolith's bearer: the bearer provider, the token store,
   * and for a shop with a token the read-only subscriptions scan.
   */
  suspend fun shopifyStoreStatus(shop: ShopDomain, traceId: String): LoopbackAnswer?

  /** A self-signed delivery for a topic the service does not subscribe to, which the handler acknowledges without work: the request shape the load balancer sends first. */
  suspend fun webhookDelivery(shop: ShopDomain, traceId: String): Int?
}

/**
 * A request to ourselves as the bearer check sees it. Two different `401`s come back from a monolith-facing route:
 * the bearer provider's challenge, a bare `401` naming our realm, which says the bearer was refused; and the handler's
 * JSON `ApiError` for a shop without an Admin token, which says the bearer passed. [bearerRejected] is the first.
 */
data class LoopbackAnswer(val status: Int, val bearerRejected: Boolean = false)
