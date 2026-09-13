package dropnext.dss.lib.shopify.token

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield


private val acme = ShopDomain.parse("acme.myshopify.com")!!
private val other = ShopDomain.parse("other.myshopify.com")!!

class InMemoryShopTokenStoreTest {

  @Test
  fun `a seeded token resolves without the fallback`() = runBlocking {
    var fallbackCalls = 0
    val store = InMemoryShopTokenStore(mapOf(acme to ShopifyAdminToken("shpat_seed"))) { fallbackCalls++; ShopLookup.Missing }
    assert(store.resolve(acme) == ShopLookup.Found(ShopifyAdminToken("shpat_seed")))
    assert(fallbackCalls == 0)
  }

  @Test
  fun `a miss asks the fallback once and remembers its answer`() = runBlocking {
    var fallbackCalls = 0
    val store = InMemoryShopTokenStore { fallbackCalls++; ShopLookup.Found(ShopifyAdminToken("shpat_from_monolith")) }
    assert(store.resolve(acme) == ShopLookup.Found(ShopifyAdminToken("shpat_from_monolith")))
    assert(store.resolve(acme) == ShopLookup.Found(ShopifyAdminToken("shpat_from_monolith")))
    assert(fallbackCalls == 1)
    assert(store.cached(acme) == ShopifyAdminToken("shpat_from_monolith"))
  }

  @Test
  fun `a fallback that knows nothing leaves the store empty`() = runBlocking {
    val store = InMemoryShopTokenStore()
    assert(store.resolve(other) == ShopLookup.Missing)
    assert(store.cached(other) == null)
  }

  /** A monolith that did not answer this request may answer the next one, so nothing is cached and it is asked again. */
  @Test
  fun `an unavailable fallback is passed on and asked again on the next resolve`() = runBlocking {
    var fallbackCalls = 0
    val store = InMemoryShopTokenStore { fallbackCalls++; ShopLookup.Unavailable }
    assert(store.resolve(acme) == ShopLookup.Unavailable)
    assert(store.resolve(acme) == ShopLookup.Unavailable)
    assert(fallbackCalls == 2)
  }

  /** Every deploy starts with an empty cache, and a burst of deliveries for one shop must not become a burst of monolith calls. */
  @Test
  fun `concurrent misses for one shop ask the fallback once`() = runBlocking {
    val fallbackCalls = AtomicInteger()
    val answer = CompletableDeferred<Unit>()
    val store = InMemoryShopTokenStore {
      fallbackCalls.incrementAndGet()
      answer.await()
      ShopLookup.Found(ShopifyAdminToken("shpat_once"))
    }

    val lookups = List(10) { async { store.resolve(acme) } }
    while (fallbackCalls.get() == 0) yield()
    answer.complete(Unit)

    assert(lookups.awaitAll().all { it == ShopLookup.Found(ShopifyAdminToken("shpat_once")) })
    assert(fallbackCalls.get() == 1)
  }

  /** A webhook that gives up on its wait, its budget having run out, must not take the lookup down with it. */
  @Test
  fun `a waiter cancelled during another caller's lookup takes nothing away from it`() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val answer = CompletableDeferred<Unit>()
    val store = InMemoryShopTokenStore {
      started.complete(Unit)
      answer.await()
      ShopLookup.Found(ShopifyAdminToken("shpat_once"))
    }

    val first = async { store.resolve(acme) }
    started.await()
    val second = async { store.resolve(acme) }
    yield() // The second is now queued behind the first's lookup.
    second.cancel()
    answer.complete(Unit)

    assert(first.await() == ShopLookup.Found(ShopifyAdminToken("shpat_once")))
    assert(second.isCancelled)
    assert(store.cached(acme) == ShopifyAdminToken("shpat_once"))
  }

  @Test
  fun `remember overrides what was seeded`() = runBlocking {
    val store = InMemoryShopTokenStore(mapOf(acme to ShopifyAdminToken("shpat_old")))
    store.remember(acme, ShopifyAdminToken("shpat_new"))
    assert(store.resolve(acme) == ShopLookup.Found(ShopifyAdminToken("shpat_new")))
  }

  @Test
  fun `forget drops the rejected token so the next resolve asks the fallback again`() = runBlocking {
    var fallbackCalls = 0
    val store = InMemoryShopTokenStore(mapOf(acme to ShopifyAdminToken("shpat_stale"))) {
      fallbackCalls++
      ShopLookup.Found(ShopifyAdminToken("shpat_fresh"))
    }

    store.forget(acme, ShopifyAdminToken("shpat_stale"))

    assert(store.cached(acme) == null)
    assert(store.resolve(acme) == ShopLookup.Found(ShopifyAdminToken("shpat_fresh")))
    assert(fallbackCalls == 1)
  }

  /** The `401` of a request that started out with the old token arrives after a reinstall remembered a new one. */
  @Test
  fun `forget leaves a token that is not the rejected one`() {
    val store = InMemoryShopTokenStore(mapOf(acme to ShopifyAdminToken("shpat_after_reinstall")))

    store.forget(acme, ShopifyAdminToken("shpat_revoked"))

    assert(store.cached(acme) == ShopifyAdminToken("shpat_after_reinstall"))
  }

  /** An OAuth callback that lands while the monolith lookup is in flight holds the newer token. */
  @Test
  fun `a token remembered during the fallback is not overwritten by the fallback's answer`() = runBlocking {
    lateinit var store: InMemoryShopTokenStore
    store = InMemoryShopTokenStore {
      store.remember(acme, ShopifyAdminToken("shpat_from_reinstall"))
      ShopLookup.Found(ShopifyAdminToken("shpat_from_monolith"))
    }

    assert(store.resolve(acme) == ShopLookup.Found(ShopifyAdminToken("shpat_from_reinstall")))
    assert(store.cached(acme) == ShopifyAdminToken("shpat_from_reinstall"))
  }

  @Test
  fun `a token never prints itself`() {
    assert(ShopifyAdminToken("shpat_secret").toString() == "***")
    assert("shpat_secret" !in "token=${ShopifyAdminToken("shpat_secret")}")
  }
}
