package dropnext.dss.domain

import org.junit.jupiter.api.Test


class ShopDomainTest {

  @Test
  fun `normalises full myshopify host`() {
    assert(ShopDomain.parse("acme.myshopify.com")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `normalises uppercase host`() {
    assert(ShopDomain.parse("ACME.MYSHOPIFY.COM")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `strips scheme from https url`() {
    assert(ShopDomain.parse("https://acme.myshopify.com")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `strips scheme from http url`() {
    assert(ShopDomain.parse("http://acme.myshopify.com")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `strips trailing path from full url`() {
    assert(ShopDomain.parse("https://acme.myshopify.com/admin/orders")?.normalizedShopifyHost == "acme.myshopify.com")
  }

  @Test
  fun `expands short handle to myshopify host`() {
    assert(ShopDomain.parse("my-store")?.normalizedShopifyHost == "my-store.myshopify.com")
  }

  @Test
  fun `expands single-character handle`() {
    assert(ShopDomain.parse("a")?.normalizedShopifyHost == "a.myshopify.com")
  }

  @Test
  fun `trims whitespace before normalising`() {
    assert(ShopDomain.parse("  acme.myshopify.com  ")?.normalizedShopifyHost == "acme.myshopify.com")
    assert(ShopDomain.parse("  my-store  ")?.normalizedShopifyHost == "my-store.myshopify.com")
  }

  @Test
  fun `returns null for bare other-host names`() {
    assert(ShopDomain.parse("example.com") == null)
    assert(ShopDomain.parse("myshopify.com") == null)
  }

  @Test
  fun `returns null for invalid handle shapes`() {
    assert(ShopDomain.parse("-leading-hyphen") == null)
    assert(ShopDomain.parse("trailing-") == null)
    assert(ShopDomain.parse("with space") == null)
    assert(ShopDomain.parse("") == null)
  }

  @Test
  fun `returns null for a foreign host that merely ends in the myshopify suffix`() {
    // Each of these would send an `/install` redirect to `evil.com`: the browser stops reading the
    // host at `#`, `?` or `\`, so what follows the suffix check is not where it goes.
    assert(ShopDomain.parse("evil.com#.myshopify.com") == null)
    assert(ShopDomain.parse("evil.com?x=.myshopify.com") == null)
    assert(ShopDomain.parse("evil.com\\.myshopify.com") == null)
    assert(ShopDomain.parse("evil.com@acme.myshopify.com") == null)
  }

  @Test
  fun `returns null for a nested or malformed subdomain on the myshopify suffix`() {
    assert(ShopDomain.parse("a.b.myshopify.com") == null)
    assert(ShopDomain.parse("-leading.myshopify.com") == null)
    assert(ShopDomain.parse("trailing-.myshopify.com") == null)
    assert(ShopDomain.parse(".myshopify.com") == null)
    assert(ShopDomain.parse("acme.myshopify.com:443") == null)
  }

  @Test
  fun `lowercases uppercase short handle`() {
    assert(ShopDomain.parse("UPPER")?.normalizedShopifyHost == "upper.myshopify.com")
  }

  @Test
  fun `subdomainShort strips the myshopify suffix`() {
    assert(ShopDomain.parse("acme.myshopify.com")!!.subdomainOnly == "acme")
  }

  @Test
  fun `subdomainShort returns short handle for short input`() {
    assert(ShopDomain.parse("acme")!!.subdomainOnly == "acme")
  }
}
