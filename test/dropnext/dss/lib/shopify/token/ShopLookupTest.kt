package dropnext.dss.lib.shopify.token

import org.junit.jupiter.api.Test


class ShopLookupTest {

  @Test
  fun `map transforms what was found`() {
    val found: ShopLookup<Int> = ShopLookup.Found(2)
    assert(found.map { it * 21 } == ShopLookup.Found(42))
  }

  /** Neither answer carries a value, and each must survive the mapping as itself: the caller acts differently on them. */
  @Test
  fun `map passes a missing and an unavailable answer on unchanged`() {
    val missing: ShopLookup<Int> = ShopLookup.Missing
    val unavailable: ShopLookup<Int> = ShopLookup.Unavailable
    assert(missing.map { it * 21 } == ShopLookup.Missing)
    assert(unavailable.map { it * 21 } == ShopLookup.Unavailable)
  }
}
