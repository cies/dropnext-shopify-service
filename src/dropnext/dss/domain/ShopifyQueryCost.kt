package dropnext.dss.domain


/**
 * What Shopify says a query cost, as it reports it on every response (`extensions.cost`).
 *
 * [requested] is computed from the selected fields before the query runs, and is what Shopify holds against its
 * 1,000-point cap for a single query: it is the same on every call of an operation with the same page sizes.
 * [actual] is what the query really cost once it ran, which depends on the shop's data, and is what the bucket is
 * charged. Each is `null` when Shopify did not report it; a throttled answer, for one, never ran.
 */
data class ShopifyQueryCost(
  val requested: Int?,
  val actual: Int?,
  val budget: ShopifyRateBudget?,
)
