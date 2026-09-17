package dropnext.dss.lib.shopify.graphql

import dropnext.dss.domain.ShopifyQueryCost
import dropnext.dss.domain.ShopifyRateBudget
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull


/**
 * Reads `extensions.cost` off a response envelope. graphql-kotlin hands the extensions over as plain maps and numbers,
 * with no generated type to lean on, so this walks the map and leaves out whatever is not the shape it expects: a
 * statistic must never be able to fail a webhook, and the block has no schema guarantee behind it.
 *
 * `null` when there is no cost block, or nothing in it could be read.
 */
fun Map<String, Any?>?.toShopifyQueryCostOrNull(): ShopifyQueryCost? {
  val cost = this?.get("cost") as? Map<*, *> ?: return null
  val requested = cost["requestedQueryCost"].asDoubleOrNull()?.toInt()
  val actual = cost["actualQueryCost"].asDoubleOrNull()?.toInt()
  val budget = (cost["throttleStatus"] as? Map<*, *>)?.toRateBudgetOrNull()
  if (requested == null && actual == null && budget == null) return null
  return ShopifyQueryCost(requested = requested, actual = actual, budget = budget)
}

private fun Map<*, *>.toRateBudgetOrNull(): ShopifyRateBudget? {
  val maximum = this["maximumAvailable"].asDoubleOrNull() ?: return null
  val available = this["currentlyAvailable"].asDoubleOrNull() ?: return null
  val restore = this["restoreRate"].asDoubleOrNull() ?: return null
  return ShopifyRateBudget(maximumAvailable = maximum, currentlyAvailable = available, restoreRate = restore)
}

/** Shopify documents the costs as integers and the bucket as floating point; either may arrive as the other. */
private fun Any?.asDoubleOrNull(): Double? = when (this) {
  is Number -> toDouble()
  is JsonPrimitive -> if (isString) null else doubleOrNull
  else -> null
}
