package dropnext.dss.lib.crypto

import org.junit.jupiter.api.Test


class ShopifyTokenFingerprintTest {

  /** The monolith's `ShopifyTokenFingerprintTest` pins the same value: the two sides must compute the same name. */
  @Test
  fun `the fingerprint is the head of the token's SHA-256, in lowercase hex`() {
    assert(shopifyTokenFingerprint("test") == "9f86d081884c7d65")
  }

  @Test
  fun `another token has another fingerprint`() {
    assert(shopifyTokenFingerprint("shpat_one") != shopifyTokenFingerprint("shpat_two"))
  }
}
