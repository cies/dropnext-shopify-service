package dropnext.dss.mapper

import dropnext.dss.contract.CreateShopifyOrderRequest
import dropnext.dss.contract.OrderLineItem
import dropnext.dss.contract.ShippingAddress
import dropnext.dss.domain.ShopifyOrderId
import dropnext.dss.domain.fulfillment.isOpenForFulfillment
import dropnext.dss.lib.shopify.legacyIdFromGid

import dropnext.graphql.generated.enums.OrderDisplayFinancialStatus
import dropnext.graphql.generated.enums.OrderDisplayFulfillmentStatus
import dropnext.graphql.generated.getorderfordss.MailingAddress
import dropnext.graphql.generated.getorderfordss.Order

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale


/** Why a Shopify line item is left out of the monolith order; the order sync logs every one. */
enum class OrderLineItemOmission {
  /** A tip, a gift card or a custom line: nothing the monolith could match to a product. Expected. */
  NO_VARIANT,

  /** The variant's `legacyResourceId` is not a number, so the monolith could not key it. */
  UNPARSEABLE_VARIANT_ID,

  /** The variant is on none of the order's fulfillment orders, open or closed. */
  NO_FULFILLMENT_ORDER,

  /** The line item's own gid carries no numeric id. */
  UNPARSEABLE_LINE_ITEM_ID,
}

/** One line item that is not in the request; [lineItemId] is the Graphql gid, which Shopify's admin shows. */
data class OmittedOrderLineItem(val lineItemId: String, val reason: OrderLineItemOmission)

/** The request plus what the mapper left out of it, so the caller can log each omission with its reason. */
data class MonolithOrderMapping(
  val request: CreateShopifyOrderRequest,
  val omittedLineItems: List<OmittedOrderLineItem>,
)

/**
 * Builds [CreateShopifyOrderRequest] from Shopify Admin Graphql [Order] (hydrated after a webhook).
 * Field names and JSON shape match the checked-in monolith contract (snake_case in wire format).
 *
 * The order is keyed by [orderId], which the caller read off the gid it loaded the snapshot with, rather than
 * re-derived from the snapshot: a snapshot without a readable id used to be sent as order `0`, and a made-up id
 * is worse than no order.
 *
 * A line item the monolith cannot take (see [OrderLineItemOmission]) is dropped rather than failing
 * the order: the rest of the order is still worth having, and the omission is reported alongside.
 */
fun mapOrderForMonolith(
  shopifySubdomain: String,
  orderId: ShopifyOrderId,
  order: Order,
): MonolithOrderMapping {
  val shipping = order.shippingAddress?.toDto() ?: ShippingAddress(
    firstName = null,
    lastName = null,
    address1 = "",
    address2 = null,
    city = "",
    province = null,
    provinceCode = null,
    countryCode = "",
    zip = null,
    phone = null,
  )
  val omitted = mutableListOf<OmittedOrderLineItem>()
  fun omit(lineItemId: String, reason: OrderLineItemOmission): OrderLineItem? {
    omitted += OmittedOrderLineItem(lineItemId, reason)
    return null
  }
  val lineItems = order.lineItems.edges.mapNotNull { edge ->
    val li = edge.node
    val variant = li.variant ?: return@mapNotNull omit(li.id, OrderLineItemOmission.NO_VARIANT)
    val variantLegacy = variant.legacyResourceId.toLongOrNull()
      ?: return@mapNotNull omit(li.id, OrderLineItemOmission.UNPARSEABLE_VARIANT_ID)
    val fulfillmentOrderId = fulfillmentOrderLegacyIdForVariant(order, variantLegacy)
      ?: return@mapNotNull omit(li.id, OrderLineItemOmission.NO_FULFILLMENT_ORDER)
    val lineId = legacyIdFromGid(li.id) ?: return@mapNotNull omit(li.id, OrderLineItemOmission.UNPARSEABLE_LINE_ITEM_ID)
    OrderLineItem(

      shopifyLineItemId = lineId,
      productVariantId = variantLegacy,
      quantity = li.quantity,
      fulfillmentOrderId = fulfillmentOrderId,
      snapshotOfVariantTitle = li.name,
      snapshotOfProductTitle = li.title,
      snapshotOfPriceAsString = shopifyMoneyAmountForWire(li.originalUnitPriceSet.shopMoney.amount),
    )
  }
  val currency = order.totalPriceSet.shopMoney.currencyCode.name
  val totalString = resolveOrderTotalAsString(
    orderTotalAmount = order.totalPriceSet.shopMoney.amount,
    lineItems = lineItems,
    currencyCode = currency,
  )
  val request = CreateShopifyOrderRequest(
    shopifySubdomain = shopifySubdomain,
    shopifyOrderId = orderId.value,
    name = order.name,
    financialStatus = order.displayFinancialStatus?.toFinancialString() ?: "Unknown",
    fulfillmentStatus = order.displayFulfillmentStatus.toMonolithFulfillmentStatus(),
    createdAt = formatCreatedAtUtcZ(order.createdAt),
    shippingAddress = shipping,
    lineItems = lineItems,
    totalAsString = totalString,
    currency = currency,
  )
  return MonolithOrderMapping(request, omitted)
}


/**
 * Shopify's order total, as it sent it. A zero is a real total (a fully discounted or comped order)
 * and is kept: the fallback that once replaced it with the undiscounted line sum reported money that
 * was never paid. Only an absent, unparseable or negative amount, which Shopify does not send, falls
 * back to the line-item unit prices × quantities so the required field is never empty.
 */
internal fun resolveOrderTotalAsString(
  orderTotalAmount: String?,
  lineItems: List<OrderLineItem>,
  currencyCode: String,
): String {
  val orderTotal = orderTotalAmount?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
  if (orderTotal != null) return orderTotal.toPlainString()
  val fractionDigits = currencyFractionDigits(currencyCode)
  if (fractionDigits == null) {
    return shopifyMoneyAmountForWire(orderTotalAmount)
  }
  val lineSumMinor = lineItems.sumOf { item ->
    shopifyAmountToMinorUnits(item.snapshotOfPriceAsString, currencyCode) * item.quantity.toLong()
  }.coerceAtLeast(0L)
  return minorUnitsToShopifyAmount(lineSumMinor, currencyCode)
}

private fun OrderDisplayFinancialStatus.toFinancialString(): String =
  if (name == "__UNKNOWN_VALUE") "Unknown" else enumNameToPascalCase(name)

/**
 * Monolith expects PascalCase fulfillment labels (`Fulfilled` / `Partial` / `Restocked`), or **`null`**
 * when nothing is shipped yet (do not send Graphql labels like `Unfulfilled`).
 */
private fun OrderDisplayFulfillmentStatus.toMonolithFulfillmentStatus(): String? =
  when (this) {
    OrderDisplayFulfillmentStatus.FULFILLED -> "Fulfilled"

    OrderDisplayFulfillmentStatus.PARTIALLY_FULFILLED,
    OrderDisplayFulfillmentStatus.IN_PROGRESS -> "Partial"

    OrderDisplayFulfillmentStatus.RESTOCKED -> "Restocked"

    OrderDisplayFulfillmentStatus.UNFULFILLED,
    OrderDisplayFulfillmentStatus.OPEN,
    OrderDisplayFulfillmentStatus.ON_HOLD,
    OrderDisplayFulfillmentStatus.SCHEDULED,
    OrderDisplayFulfillmentStatus.PENDING_FULFILLMENT,
    OrderDisplayFulfillmentStatus.REQUEST_DECLINED,
    OrderDisplayFulfillmentStatus.__UNKNOWN_VALUE -> null
  }

/**
 * The fulfillment order the monolith should file the line under. An open one wins over a closed or
 * cancelled one holding the same variant (a location move leaves both on the order); when none is
 * open, as on an order that was already fulfilled when its webhook arrived, the first one still
 * names where the units went. `null` only when no fulfillment order holds the variant at all.
 */
private fun fulfillmentOrderLegacyIdForVariant(order: Order, legacyVariantId: Long): Long? {
  val holdingTheVariant = order.fulfillmentOrders.edges.map { it.node }.filter { fulfillmentOrder ->
    fulfillmentOrder.lineItems.edges.any { it.node.variant?.legacyResourceId?.toLongOrNull() == legacyVariantId }
  }
  val chosen = holdingTheVariant.firstOrNull { it.status.isOpenForFulfillment() }
    ?: holdingTheVariant.firstOrNull()
    ?: return null
  return legacyIdFromGid(chosen.id)
}


private fun MailingAddress.toDto(): ShippingAddress =
  ShippingAddress(
    firstName = firstName,
    lastName = lastName,
    address1 = address1.orEmpty(),
    address2 = address2,
    city = city.orEmpty(),
    province = province,
    provinceCode = provinceCode,
    countryCode = countryCodeV2?.name.orEmpty(),
    zip = zip,
    phone = phone,
  )

/** Converts Graphql enum names (`PARTIALLY_PAID`) to monolith PascalCase (`PartiallyPaid`). */
private fun enumNameToPascalCase(enumName: String): String =
  enumName.split('_').joinToString("") { part ->
    part.lowercase(Locale.ROOT).replaceFirstChar { char -> char.titlecase(Locale.ROOT) }
  }

/** Normalizes Shopify Admin `DateTime` strings to UTC `…Z` (second precision), e.g. `2026-04-25T10:30:00Z`. */
private fun formatCreatedAtUtcZ(createdAt: String): String =
  try {
    val instant = OffsetDateTime.parse(createdAt).toInstant().truncatedTo(ChronoUnit.SECONDS)
    DateTimeFormatter.ISO_INSTANT.format(instant)
  } catch (_: Exception) {
    createdAt
  }
