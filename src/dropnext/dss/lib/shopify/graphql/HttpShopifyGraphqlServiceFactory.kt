package dropnext.dss.lib.shopify.graphql

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dropnext.dss.domain.ShopDomain
import dropnext.dss.lib.shopify.oauth.adminGraphqlUrl
import dropnext.dss.lib.shopify.token.ShopLookup
import dropnext.dss.lib.shopify.token.ShopTokenStore
import dropnext.dss.lib.shopify.token.map
import io.ktor.client.HttpClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap


/**
 * Production [ShopifyGraphqlServiceFactory]:
 * - resolves the Admin token through [tokens],
 * - caches a per-shop [GraphQLKtorClient] (inexpensive reuse on each call), and
 * - hands back an [HttpShopifyGraphqlService] bound to that token.
 */
class HttpShopifyGraphqlServiceFactory(
  httpClient: HttpClient,
  private val tokens: ShopTokenStore,
  private val apiVersion: String,
) : ShopifyGraphqlServiceFactory {

  private val gqlClientCache = GraphqlClientCache(httpClient)

  // One for every shop's service: what it has already reported has to outlive the per-request services.
  private val costReporter = ShopifyQueryCostReporter()

  override suspend fun forShop(shop: ShopDomain): ShopLookup<ShopifyGraphqlService> =
    tokens.resolve(shop).map { token ->
      HttpShopifyGraphqlService(
        shop = shop,
        gqlClient = gqlClientCache.forShop(shop, apiVersion),
        accessToken = token,
        // A rejected token is dropped so the next request re-resolves it from the monolith, which may
        // hold a newer one from a reinstall this instance never saw.
        onTokenRejected = { tokens.forget(shop, token) },
        costReporter = costReporter,
      )
    }
}

/**
 * Caches [GraphQLKtorClient] instances keyed by shop + API version so a new client object is not allocated on every request.
 *
 * The underlying [httpClient] is shared (one OkHttp connection pool),
 * so the only thing actually cached is the per-shop URL binding plus a small wrapper.
 *
 * Intentionally does NOT implement `Closeable`:
 * `GraphQLKtorClient.close()` delegates to `httpClient.close()` on the *shared* client,
 * which `DssDependencies.close()` already closes once.
 * Calling `close()` on every cache entry would close the shared client repeatedly.
 */
private class GraphqlClientCache(private val httpClient: HttpClient) {
  private val cache = ConcurrentHashMap<String, GraphQLKtorClient>()

  fun forShop(shop: ShopDomain, apiVersion: String): GraphQLKtorClient =
    cache.getOrPut("${shop.normalizedShopifyHost}/$apiVersion") {
      GraphQLKtorClient(URI(shop.adminGraphqlUrl(apiVersion)).toURL(), httpClient)
    }
}
