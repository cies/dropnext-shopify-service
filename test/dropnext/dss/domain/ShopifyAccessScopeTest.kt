package dropnext.dss.domain

import dropnext.dss.testutil.helper.projectRoot
import java.io.File
import org.junit.jupiter.api.Test


/** The one list of access scopes the install asks for, and when a grant covers it. */
class ShopifyAccessScopeTest {

  private val everyHandle = ShopifyAccessScope.entries.map { it.handle }

  @Test
  fun `a grant of every handle misses nothing`() {
    assert(missingShopifyAccessScopes(everyHandle).isEmpty())
  }

  @Test
  fun `an empty grant misses every scope, in the order the install asks for them`() {
    assert(missingShopifyAccessScopes(emptyList()) == ShopifyAccessScope.entries)
  }

  /** Shopify may answer only the write scope of a resource it granted both for, since writing includes reading. */
  @Test
  fun `a write scope covers the read scope of the same resource`() {
    val granted = everyHandle - "read_products" + "write_products"
    assert(missingShopifyAccessScopes(granted).isEmpty())
  }

  @Test
  fun `a read scope does not cover the write scope of the same resource`() {
    val granted = everyHandle - "write_merchant_managed_fulfillment_orders" + "read_merchant_managed_fulfillment_orders"
    assert(missingShopifyAccessScopes(granted) == listOf(ShopifyAccessScope.WRITE_MERCHANT_MANAGED_FULFILLMENT_ORDERS))
  }

  @Test
  fun `a handle counts trimmed and an empty one covers nothing`() {
    val granted = everyHandle.map { " $it " } - " write_fulfillments " + ""
    assert(missingShopifyAccessScopes(granted) == listOf(ShopifyAccessScope.WRITE_FULFILLMENTS))
  }

  @Test
  fun `a handle granted twice misses each scope once`() {
    assert(missingShopifyAccessScopes(listOf("read_orders", "read_orders")).size == ShopifyAccessScope.entries.size - 1)
  }

  @Test
  fun `a handle the service does not ask for is ignored`() {
    assert(missingShopifyAccessScopes(everyHandle + "write_webhooks").isEmpty())
  }

  /** The Partner Dashboard is configured from the README, so its list has to be this one. */
  @Test
  fun `the README's Shopify Partner app section names exactly these scopes`() {
    val readme = File(projectRoot, "README.md").readText()
    assert("### Shopify Partner app" in readme)
    val section = readme.substringAfter("### Shopify Partner app").substringBefore("\n### ")
    val named = Regex("""`((?:read|write)_\w+)`""").findAll(section).map { it.groupValues[1] }.toSet()
    assert(named == everyHandle.toSet())
  }
}
