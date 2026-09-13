package dropnext.dss.lib.ktor

import dropnext.dss.domain.MonolithToDssApiKey
import dropnext.dss.lib.crypto.constantTimeEquals
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer


/** The provider name a monolith-facing route group passes to `authenticate(...)`. */
const val MONOLITH_WEBHOOK_AUTH = "monolith-webhook-auth"

/**
 * Every route inside `authenticate(MONOLITH_WEBHOOK_AUTH)` must carry `Authorization: Bearer <MONOLITH_TO_DSS_API_KEY>`,
 * compared in constant time. A missing or wrong token is answered before the handler runs with the RFC 6750
 * challenge: a bare `401` carrying `WWW-Authenticate: Bearer realm="dss-internal"`.
 *
 * Follow-up hardening option: Stripe-style HMAC signatures over timestamp + body would add replay
 * protection and keep the shared secret off the wire.
 */
fun Application.installMonolithWebhookAuth(secret: MonolithToDssApiKey) {
  install(Authentication) {
    bearer(MONOLITH_WEBHOOK_AUTH) {
      realm = "dss-internal"
      authenticate { credential ->
        if (constantTimeEquals(secret.value, credential.token)) UserIdPrincipal("monolith") else null
      }
    }
  }
}
