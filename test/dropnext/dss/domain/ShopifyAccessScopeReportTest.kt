package dropnext.dss.domain

import org.junit.jupiter.api.Test


class ShopifyAccessScopeReportTest {

  @Test
  fun `a grant of every required scope is complete`() {
    val report = ShopifyAccessScopeReport.from(ShopifyAccessScope.entries.map { it.handle })
    assert(report.isComplete)
    assert(report.missing.isEmpty())
  }

  @Test
  fun `an empty grant misses every required scope`() {
    val report = ShopifyAccessScopeReport.from(emptyList())
    assert(!report.isComplete)
    assert(report.missing == ShopifyAccessScope.entries)
  }

  /** The check answers what Shopify granted, handles the service does not need included. */
  @Test
  fun `the granted handles are kept as given`() {
    val granted = listOf("read_orders", "unauthenticated_read_product_listings")
    assert(ShopifyAccessScopeReport.from(granted).grantedHandles == granted)
  }
}
