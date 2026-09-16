package dropnext.dss.workflow

import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.token.ShopLookup
import dropnext.dss.testutil.fake.FakeMonolithService
import dropnext.dss.testutil.fixture.ACME_SHOP
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


/**
 * The token store's fallback, which is how a restarted instance recovers the tokens it held in memory. It must not
 * raise, and it must tell "the monolith has no token" from "the monolith could not be asked": the caller is a webhook
 * handler, which acknowledges the first and asks Shopify to redeliver the second.
 */
class ResolveShopTokenFromMonolithTest {

  @Test
  fun `a store the monolith knows answers its api key`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreToken = ShopifyAdminToken("shpat_from_monolith") }
    assert(resolveShopTokenFromMonolith(monolith, ACME_SHOP) == ShopLookup.Found(ShopifyAdminToken("shpat_from_monolith")))
  }

  /** The monolith identifies a store by its subdomain; sending the full host would find nothing. */
  @Test
  fun `the lookup asks by subdomain rather than by the myshopify host`() = runBlocking {
    val monolith = FakeMonolithService()
    resolveShopTokenFromMonolith(monolith, ACME_SHOP)
    assert(monolith.getStoreCalls.single() == "acme")
  }

  @Test
  fun `a store the monolith does not know is missing`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreReturnsNotFound = true }
    assert(resolveShopTokenFromMonolith(monolith, ACME_SHOP) == ShopLookup.Missing)
  }

  @Test
  fun `a known store with no api key is missing`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreToken = null }
    assert(resolveShopTokenFromMonolith(monolith, ACME_SHOP) == ShopLookup.Missing)
  }

  @Test
  fun `an unreachable monolith is unavailable rather than missing`() = runBlocking {
    val monolith = FakeMonolithService().apply { getStoreTransportFailure = true }
    assert(resolveShopTokenFromMonolith(monolith, ACME_SHOP) == ShopLookup.Unavailable)
  }
}
