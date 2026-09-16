package dropnext.dss.mapper

import org.junit.jupiter.api.Test


class ShopifyMoneyAmountForWireTest {

  @Test
  fun `converts plain decimal to minor units for USD`() {
    assert(shopifyAmountToMinorUnits("12.34", "USD") == 1234L)
    assert(shopifyAmountToMinorUnits("0.01", "USD") == 1L)
    assert(shopifyAmountToMinorUnits("100", "USD") == 10_000L)
  }

  @Test
  fun `JPY uses zero decimal places`() {
    assert(shopifyAmountToMinorUnits("100", "JPY") == 100L)
    assert(minorUnitsToShopifyAmount(100L, "JPY") == "100")
  }

  @Test
  fun `BHD uses three decimal places`() {
    assert(shopifyAmountToMinorUnits("1.234", "BHD") == 1234L)
    assert(minorUnitsToShopifyAmount(1234L, "BHD") == "1.234")
  }

  @Test
  fun `unknown currency does not guess 100-based cents`() {
    assert(shopifyAmountToMinorUnits("12.34", "NOTACURRENCY") == 0L)
    assert(shopifyAmountToMinorUnits("12.34", "__UNKNOWN_VALUE") == 0L)
  }

  @Test
  fun `rounds half-cent up`() {
    assert(shopifyAmountToMinorUnits("19.995", "USD") == 2000L)
    assert(shopifyAmountToMinorUnits("19.999", "USD") == 2000L)
  }

  @Test
  fun `rounds sub-half-cent down`() {
    assert(shopifyAmountToMinorUnits("19.994", "USD") == 1999L)
    assert(shopifyAmountToMinorUnits("19.991", "USD") == 1999L)
  }

  @Test
  fun `zero amount maps to zero minor units`() {
    assert(shopifyAmountToMinorUnits("0", "USD") == 0L)
    assert(shopifyAmountToMinorUnits("0.00", "USD") == 0L)
  }

  @Test
  fun `negative amount preserves sign`() {
    assert(shopifyAmountToMinorUnits("-5.00", "USD") == -500L)
    assert(shopifyAmountToMinorUnits("-0.01", "USD") == -1L)
  }

  @Test
  fun `unparseable amount returns zero`() {
    assert(shopifyAmountToMinorUnits("n/a", "USD") == 0L)
    assert(shopifyAmountToMinorUnits("", "USD") == 0L)
    assert(shopifyAmountToMinorUnits("USD 12.34", "USD") == 0L)
  }

  @Test
  fun `shopifyMoneyAmountForWire passes through non-blank amounts`() {
    assert(shopifyMoneyAmountForWire("19.99") == "19.99")
    assert(shopifyMoneyAmountForWire("  10.00  ") == "10.00")
    assert(shopifyMoneyAmountForWire("n/a") == "n/a")
  }

  @Test
  fun `shopifyMoneyAmountForWire defaults blank to zero decimal`() {
    assert(shopifyMoneyAmountForWire(null) == "0.00")
    assert(shopifyMoneyAmountForWire("") == "0.00")
    assert(shopifyMoneyAmountForWire("   ") == "0.00")
  }

  @Test
  fun `minorUnitsToShopifyAmount formats cents for USD`() {
    assert(minorUnitsToShopifyAmount(1234L, "USD") == "12.34")
    assert(minorUnitsToShopifyAmount(1L, "USD") == "0.01")
    assert(minorUnitsToShopifyAmount(10_000L, "USD") == "100.00")
    assert(minorUnitsToShopifyAmount(0L, "USD") == "0.00")
  }

  /**
   * The two directions disagree on purpose. Reading an amount off Shopify is upstream data, so an
   * unusable one answers zero; formatting one is our own arithmetic, and a negative or unsizeable
   * value there is a bug that must not be padded into a plausible-looking price.
   */
  @Test
  fun `minorUnitsToShopifyAmount refuses negative minor units`() {
    assert(runCatching { minorUnitsToShopifyAmount(-1L, "USD") }.exceptionOrNull() is IllegalArgumentException)
  }

  @Test
  fun `minorUnitsToShopifyAmount refuses a currency it cannot size`() {
    assert(runCatching { minorUnitsToShopifyAmount(1234L, "NOTACURRENCY") }.exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun `a blank currency code is unknown`() {
    assert(shopifyAmountToMinorUnits("12.34", "") == 0L)
    assert(shopifyAmountToMinorUnits("12.34", "   ") == 0L)
  }

  /** ISO 4217 gives the pseudo-currencies no minor unit at all, and a guessed exponent would move the decimal point. */
  @Test
  fun `a pseudo-currency without minor units is unknown`() {
    assert(shopifyAmountToMinorUnits("12.34", "XXX") == 0L)
  }

  @Test
  fun `a currency code is recognized through the whitespace around it`() {
    assert(shopifyAmountToMinorUnits("12.34", " USD ") == 1234L)
  }
}
