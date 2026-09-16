package dropnext.dss.lib.shopify.webhook

import dropnext.dss.domain.ShopifyProductId
import org.junit.jupiter.api.Test


class GraphqlResourceIdFromShopifyWebhookTest {

  @Test
  fun `graphqlResourceIdFromShopifyWebhook returns admin_graphql_api_id when present`() {
    val id = graphqlResourceIdFromShopifyWebhook(
      topic = "orders/create",
      bodyUtf8 = """{"id":1001,"admin_graphql_api_id":"gid://shopify/Order/1001"}""",
    )
    assert(id == "gid://shopify/Order/1001")
  }

  @Test
  fun `graphqlResourceIdFromShopifyWebhook builds gid from numeric id by topic`() {
    val orderId = graphqlResourceIdFromShopifyWebhook("orders/updated", """{"id":2002}""")
    assert(orderId == "gid://shopify/Order/2002")
    val productId = graphqlResourceIdFromShopifyWebhook("products/update", """{"id":3003}""")
    assert(productId == "gid://shopify/Product/3003")
  }

  @Test
  fun `graphqlResourceIdFromShopifyWebhook returns null on unknown topic without admin_graphql_api_id`() {
    assert(graphqlResourceIdFromShopifyWebhook("customers/create", """{"id":4004}""") == null)
  }

  /** Shopify's `products/delete` body is the id and nothing else. */
  @Test
  fun `productIdFromProductWebhook reads the product id from an id-only body`() {
    assert(productIdFromProductWebhook("""{"id":788032119674292922}""") == ShopifyProductId(788032119674292922L))
  }

  @Test
  fun `productIdFromProductWebhook reads a quoted id too`() {
    assert(productIdFromProductWebhook("""{"id":"503"}""") == ShopifyProductId(503L))
  }

  @Test
  fun `productIdFromProductWebhook returns null without a numeric id`() {
    assert(productIdFromProductWebhook("""{"variants":[{"id":101}]}""") == null)
    assert(productIdFromProductWebhook("""{"id":"gid://shopify/Product/503"}""") == null)
    assert(productIdFromProductWebhook("not json") == null)
  }
}
