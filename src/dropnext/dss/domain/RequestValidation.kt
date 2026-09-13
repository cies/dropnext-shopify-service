package dropnext.dss.domain


/**
 * What a domain validator answers.
 *
 * Validators stay Ktor-free; `lib/ktor/installRequestValidation.kt` runs them on every decoded body
 * and turns an [Invalid] into the `400`, so a handler only ever sees a request that passed.
 */
sealed interface RequestValidation {
  data object Valid : RequestValidation

  data class Invalid(val messages: List<String>) : RequestValidation {
    init { require(messages.isNotEmpty()) }

    /** All messages joined for use in a single error body. */
    val message: String get() = messages.joinToString("; ")
  }
}

/** How every validator ends: a list of complaints, empty when there are none. */
fun List<String>.toRequestValidation(): RequestValidation =
  if (isEmpty()) RequestValidation.Valid else RequestValidation.Invalid(this)

/** Shared by every request that names a Shopify order: its ids are positive. */
internal fun validateShopifyOrderId(shopifyOrderId: Long): List<String> =
  if (shopifyOrderId <= 0L) listOf("invalid shopify_order_id: must be positive") else emptyList()
