package dropnext.dss.lib.crypto

import org.junit.jupiter.api.Test


class ConstantTimeEqualsTest {

  @Test
  fun `equal strings compare true`() {
    assert(constantTimeEquals("hunter2", "hunter2"))
  }

  @Test
  fun `differing strings compare false`() {
    assert(!constantTimeEquals("hunter2", "hunter3"))
  }

  @Test
  fun `length mismatch compares false`() {
    assert(!constantTimeEquals("short", "shorter"))
    assert(!constantTimeEquals("longer", "long"))
  }

  @Test
  fun `empty strings compare true`() {
    assert(constantTimeEquals("", ""))
  }

  @Test
  fun `multibyte utf8 strings compare true`() {
    assert(constantTimeEquals("héllo wörld", "héllo wörld"))
  }

  @Test
  fun `multibyte utf8 single-byte difference compares false`() {
    assert(!constantTimeEquals("héllo", "hello"))
  }
}
