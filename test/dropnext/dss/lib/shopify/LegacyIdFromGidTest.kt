package dropnext.dss.lib.shopify

import dropnext.dss.domain.ShopifyOrderId
import org.junit.jupiter.api.Test


class LegacyIdFromGidTest {

  @Test
  fun `legacyIdFromGid extracts the trailing numeric id`() {
    assert(legacyIdFromGid("gid://shopify/Order/123") == 123L)
    assert(legacyIdFromGid("gid://shopify/Product/9876543210") == 9876543210L)
  }

  @Test
  fun `legacyIdFromGid returns null when no numeric tail is present`() {
    assert(legacyIdFromGid("gid://shopify/Order/") == null)
    assert(legacyIdFromGid("gid://shopify/Order/abc") == null)
    assert(legacyIdFromGid("") == null)
  }

  @Test
  fun `orderGid round-trips through legacyIdFromGid`() {
    assert(legacyIdFromGid(orderGid(ShopifyOrderId(1001L))) == 1001L)
    assert(orderGid(ShopifyOrderId(1001L)) == "gid://shopify/Order/1001")
  }
}
