package dropnext.dss.lib.shopify.graphql

import dropnext.dss.domain.ShopifyQueryCost
import dropnext.dss.domain.ShopifyRateBudget
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test


class ToShopifyQueryCostOrNullTest {

  private val throttleStatus = mapOf("maximumAvailable" to 2000.0, "currentlyAvailable" to 1954.0, "restoreRate" to 100.0)
  private val budget = ShopifyRateBudget(maximumAvailable = 2000.0, currentlyAvailable = 1954.0, restoreRate = 100.0)

  @Test
  fun `a full cost block is read`() {
    val extensions = mapOf(
      "cost" to mapOf("requestedQueryCost" to 101, "actualQueryCost" to 46, "throttleStatus" to throttleStatus),
    )
    assert(extensions.toShopifyQueryCostOrNull() == ShopifyQueryCost(requested = 101, actual = 46, budget = budget))
  }

  @Test
  fun `a cost block without a throttle status has costs and no budget`() {
    val extensions = mapOf("cost" to mapOf("requestedQueryCost" to 101, "actualQueryCost" to 46))
    assert(extensions.toShopifyQueryCostOrNull() == ShopifyQueryCost(requested = 101, actual = 46, budget = null))
  }

  /** A throttled answer never ran, so Shopify reports no actual cost for it. */
  @Test
  fun `a cost block without an actual cost still carries the budget`() {
    val extensions = mapOf("cost" to mapOf("requestedQueryCost" to 101, "throttleStatus" to throttleStatus))
    assert(extensions.toShopifyQueryCostOrNull() == ShopifyQueryCost(requested = 101, actual = null, budget = budget))
  }

  @Test
  fun `no extensions, or no cost in them, is no cost`() {
    assert((null as Map<String, Any?>?).toShopifyQueryCostOrNull() == null)
    assert(mapOf<String, Any?>("other" to 1).toShopifyQueryCostOrNull() == null)
  }

  @Test
  fun `floats where integers are documented, and integers where floats are, are both read`() {
    val extensions = mapOf(
      "cost" to mapOf(
        "requestedQueryCost" to 101.0,
        "actualQueryCost" to JsonPrimitive(46),
        "throttleStatus" to mapOf("maximumAvailable" to 2000, "currentlyAvailable" to 1954L, "restoreRate" to 100),
      ),
    )
    assert(extensions.toShopifyQueryCostOrNull() == ShopifyQueryCost(requested = 101, actual = 46, budget = budget))
  }

  @Test
  fun `a cost block of the wrong shape is no cost`() {
    assert(mapOf("cost" to "not a map").toShopifyQueryCostOrNull() == null)
    assert(mapOf("cost" to mapOf("requestedQueryCost" to "lots")).toShopifyQueryCostOrNull() == null)
  }

  @Test
  fun `a throttle status of the wrong shape is no budget`() {
    val extensions = mapOf(
      "cost" to mapOf("requestedQueryCost" to 5, "throttleStatus" to mapOf("maximumAvailable" to "lots")),
    )
    assert(extensions.toShopifyQueryCostOrNull() == ShopifyQueryCost(requested = 5, actual = null, budget = null))
  }
}
