package dropnext.dss.routing

import dropnext.dss.handler.WebhookSubscriptionHandlers
import dropnext.dss.lib.ktor.MONOLITH_WEBHOOK_AUTH
import dropnext.dss.path.Paths
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post


/**
 * Both routes drive a monolith lookup and Shopify calls and say whether a shop is installed, and the registration
 * changes the shop's subscriptions: not for anyone who can reach the service.
 */
fun Route.webhookSubscriptionRoutes(handlers: WebhookSubscriptionHandlers) {
  authenticate(MONOLITH_WEBHOOK_AUTH) {
    get(Paths.apiCheck) { handlers.handleApiCheck(call) }
    post(Paths.apiWebhooksRegister) { handlers.handleRegisterWebhooks(call) }
  }
}
