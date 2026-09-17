package dropnext.dss.domain


// Shopify's numeric ("legacy") ids and the monolith's store id, each its own type so that two
// adjacent `Long` parameters cannot be swapped without the build noticing. The contract DTOs keep
// the primitive; handlers and workflows wrap at the boundary.

@JvmInline
value class ShopifyOrderId(val value: Long) {
  override fun toString() = value.toString()
}

@JvmInline
value class ShopifyShopId(val value: Long) {
  override fun toString() = value.toString()
}

@JvmInline
value class ShopifyProductId(val value: Long) {
  override fun toString() = value.toString()
}

@JvmInline
value class ShopifyVariantId(val value: Long) {
  override fun toString() = value.toString()
}

@JvmInline
value class ShopifyFulfillmentId(val value: Long) {
  override fun toString() = value.toString()
}

@JvmInline
value class ShopifyFulfillmentEventId(val value: Long) {
  override fun toString() = value.toString()
}

/** The monolith's own id for a store row. */
@JvmInline
value class StoreId(val value: Long) {
  override fun toString() = value.toString()
}
