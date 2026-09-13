package dropnext.dss.lib.shopify.token

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * In-memory [ShopTokenStore], as the DSS has no database (it is stateless): basically a cache.
 *
 * Seeded from `DSS_SHOP_ACCESS_TOKENS`, filled by the OAuth callback and `PUT /stores/api-key`,
 * and —on a miss— by [fallback], which production wires to a monolith lookup so a restarted instance recovers its tokens.
 *
 * Backed by a [ConcurrentHashMap] so callbacks, webhook handlers and the fallback can write concurrently.
 * Keys are canonical [ShopDomain] values, so case-insensitive lookups are unnecessary.
 */
class InMemoryShopTokenStore(
  initial: Map<ShopDomain, ShopifyAdminToken> = emptyMap(),
  private val fallback: suspend (ShopDomain) -> ShopLookup<ShopifyAdminToken> = { ShopLookup.Missing },
) : ShopTokenStore {
  private val tokens: ConcurrentHashMap<ShopDomain, ShopifyAdminToken> = ConcurrentHashMap(initial)

  /** One per shop that ever missed: a mutex is a few words, and the set of shops is small. */
  private val lookups = ConcurrentHashMap<ShopDomain, Mutex>()

  override suspend fun resolve(shop: ShopDomain): ShopLookup<ShopifyAdminToken> {
    tokens[shop]?.let { return ShopLookup.Found(it) }
    // One lookup per shop at a time: a burst of deliveries for a shop this instance does not know yet (every deploy
    // starts with an empty cache) asks the monolith once, and the rest find the answer cached when their turn comes.
    // A mutex rather than one shared job, so a caller that is cancelled while it waits (a webhook past its budget)
    // takes nothing away from the others.
    return lookups.computeIfAbsent(shop) { Mutex() }.withLock {
      tokens[shop]?.let { return ShopLookup.Found(it) }
      when (val fetched = fallback(shop)) {
        // `putIfAbsent`, not `put`: a `remember` that landed while the fallback was in flight (an OAuth
        // callback, a `PUT /stores/api-key`) is newer than what the monolith answered and must win.
        is ShopLookup.Found -> ShopLookup.Found(tokens.putIfAbsent(shop, fetched.value) ?: fetched.value)
        // Neither is cached: the shop may be installed a minute from now, and a monolith that did not answer this
        // request may answer the next one.
        ShopLookup.Missing, ShopLookup.Unavailable -> fetched
      }
    }
  }

  override fun remember(shop: ShopDomain, token: ShopifyAdminToken) {
    tokens[shop] = token
  }

  override fun forget(shop: ShopDomain, token: ShopifyAdminToken) {
    tokens.remove(shop, token)
  }

  /** What is cached right now, without consulting the fallback — for diagnostics and tests. */
  fun cached(shop: ShopDomain): ShopifyAdminToken? = tokens[shop]
}
