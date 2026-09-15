package dropnext.dss.testutil.fake

import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.lib.shopify.oauth.OAuthResult
import dropnext.dss.lib.shopify.oauth.ShopifyAccessGrant
import dropnext.dss.lib.shopify.oauth.ShopifyOAuthService
import java.time.Instant


/**
 * In-memory [ShopifyOAuthService]: the state is a plain string per shop, so a callback test signs one
 * without a fake Shopify server and a rewriting client, and the code exchange answers what the test
 * configured. Expiry and tampering of the real signature are the Http implementation's, tested there.
 */
class FakeShopifyOAuthService : ShopifyOAuthService, RecordingFake {

  var exchangeCodeResult: OAuthResult<ShopifyAccessGrant> = Success(completeGrant())
  val exchangeCodeCalls: MutableList<RecordedCodeExchange> = mutableListOf()

  override fun authorizeUrl(shop: ShopDomain, state: String): String =
    "https://${shop.normalizedShopifyHost}/admin/oauth/authorize?client_id=fake&state=$state"

  override fun signedState(shop: ShopDomain, now: Instant): String = "state-for-${shop.normalizedShopifyHost}"

  override fun isSignedStateValid(state: String, expectedShop: ShopDomain, now: Instant): Boolean =
    state == signedState(expectedShop, now)

  override suspend fun exchangeCode(shop: ShopDomain, code: String): OAuthResult<ShopifyAccessGrant> {
    exchangeCodeCalls.add(RecordedCodeExchange(shop = shop, code = code))
    return exchangeCodeResult
  }

  override fun clear() {
    exchangeCodeCalls.clear()
    exchangeCodeResult = Success(completeGrant())
  }
}

data class RecordedCodeExchange(val shop: ShopDomain, val code: String)

/** The fake's token with every scope the install asks for, so a callback test sees a complete grant unless it sets another. */
private fun completeGrant(): ShopifyAccessGrant =
  ShopifyAccessGrant(ShopifyAdminToken("shpat_fake_admin_token"), ShopifyAccessScope.entries.map { it.handle })
