package dropnext.dss.domain

import dropnext.dss.contract.RegisterShopifyWebhooksRequest


/** A blank subdomain names no shop; refusing it here keeps it from reaching the token lookup as one. */
fun validateRegisterShopifyWebhooksRequest(request: RegisterShopifyWebhooksRequest): RequestValidation {
  val errors = mutableListOf<String>()
  if (request.shopifySubdomain.isBlank()) errors += "shopify_subdomain is required"
  return errors.toRequestValidation()
}
