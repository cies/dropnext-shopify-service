package dropnext.dss.boot.warmup

import dropnext.dss.domain.ShopDomain


/**
 * The two requests the warm-up sends the service itself, so the inbound pipeline (the parser, the plugins, the bearer
 * provider, the HMAC, the JSON answer) runs once end to end before the first real delivery does. Each answers the HTTP
 * status, or `null` when no answer came; the caller decides what a status means.
 *
 * Production wires [HttpWarmUpLoopbackService] (plain HTTP to `127.0.0.1`); tests wire `FakeWarmUpLoopbackService`.
 */
interface WarmUpLoopbackService {
  /** `GET /api/check?shop=` with the monolith's bearer: the token store, and for a shop with a token the read-only subscriptions scan. */
  suspend fun apiCheck(shop: ShopDomain, traceId: String): Int?

  /** A self-signed delivery for a topic the service does not subscribe to, which the handler acknowledges without work: the request shape the load balancer sends first. */
  suspend fun webhookDelivery(shop: ShopDomain, traceId: String): Int?
}
