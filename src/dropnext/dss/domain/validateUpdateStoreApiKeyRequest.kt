package dropnext.dss.domain

import dropnext.dss.contract.UpdateStoreApiKeyRequest


/**
 * The handler caches the token before it does anything else, so a body that decodes but carries
 * nothing usable would replace a working token with a blank one and the shop would answer `401`
 * until the next resolve. Runs through the `RequestValidation` plugin, before the handler.
 */
fun validateUpdateStoreApiKeyRequest(request: UpdateStoreApiKeyRequest): RequestValidation {
  val errors = mutableListOf<String>()
  if (request.shopifySubdomain.isBlank()) errors += "shopify_subdomain is required"
  if (request.apiKey.isBlank()) errors += "api_key is required"
  // Null means "not known"; zero was the old way of saying that and now marks a caller that was not updated.
  val shopId = request.shopifyShopId
  if (shopId != null && shopId <= 0L) errors += "invalid shopify_shop_id: must be positive or null"
  return errors.toRequestValidation()
}
