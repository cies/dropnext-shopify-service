package dropnext.dss.routing

import dropnext.dss.handler.ShopifyStoreHandlers
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
fun Route.shopifyStoreRoutes(handlers: ShopifyStoreHandlers) {
  authenticate(MONOLITH_WEBHOOK_AUTH) {
    get(Paths.storesShopifyStatus) { handlers.handleShopifyStoreStatus(call) }
    post(Paths.storesShopifyWebhooksRegister) { handlers.handleRegisterShopifyWebhooks(call) }
  }
}
