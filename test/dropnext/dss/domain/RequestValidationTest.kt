package dropnext.dss.domain

import kotlin.test.Test


class RequestValidationTest {

  @Test
  fun `no complaints is valid, any complaint is invalid`() {
    assert(emptyList<String>().toRequestValidation() == RequestValidation.Valid)
    assert(listOf("a").toRequestValidation() == RequestValidation.Invalid(listOf("a")))
  }

  @Test
  fun `an invalid result joins its messages into the one error body`() {
    assert(RequestValidation.Invalid(listOf("a", "b")).message == "a; b")
  }

  /** An `Invalid` without a reason would answer a `400` that says nothing. */
  @Test
  fun `an invalid result without a message is a bug`() {
    assert(runCatching { RequestValidation.Invalid(emptyList()) }.exceptionOrNull() is IllegalArgumentException)
  }

  @Test
  fun `a shopify order id is positive`() {
    assert(validateShopifyOrderId(1L).isEmpty())
    assert(validateShopifyOrderId(0L).single() == "invalid shopify_order_id: must be positive")
    assert(validateShopifyOrderId(-1L).size == 1)
  }
}
