package dropnext.dss.lib.shopify.oauth

import dropnext.dss.domain.ShopDomain
import org.junit.jupiter.api.Test


class OutBoundShopifyOAuthPathsTest {

  @Test
  fun `adminGraphqlUrl composes the per-shop endpoint`() {
    val url = ShopDomain.parse("acme.myshopify.com")!!.adminGraphqlUrl("2026-04")
    assert(url == "https://acme.myshopify.com/admin/api/2026-04/graphql.json")
  }
}
