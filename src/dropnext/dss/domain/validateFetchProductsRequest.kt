package dropnext.dss.domain

import dropnext.dss.contract.FetchProductsRequest


/**
 * The cap is about the answer, not the work: a product repeats its whole description on every one of its variants, so
 * a handful of large products is already a response worth megabytes. It is deliberately smaller than it could be —
 * the caller has time, and more round trips cost nothing it cannot afford.
 */
const val MAX_PRODUCTS_PER_FETCH: Int = 10

/** Runs through the `RequestValidation` plugin, before the handler, so a handler only sees a batch it can answer. */
fun validateFetchProductsRequest(request: FetchProductsRequest): RequestValidation {
  val errors = mutableListOf<String>()
  if (request.shopifySubdomain.isBlank()) errors += "shopify_subdomain is required"
  // A caller with nothing to fetch should not call; an empty batch is a bug on its side, not an empty answer.
  if (request.shopifyProductIds.isEmpty()) errors += "shopify_product_ids is required"
  if (request.shopifyProductIds.size > MAX_PRODUCTS_PER_FETCH) {
    errors += "shopify_product_ids holds more than $MAX_PRODUCTS_PER_FETCH ids"
  }
  if (request.shopifyProductIds.any { it <= 0L }) errors += "invalid shopify_product_ids: every id must be positive"
  return errors.toRequestValidation()
}
