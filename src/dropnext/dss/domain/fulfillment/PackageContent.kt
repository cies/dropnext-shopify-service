package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment


/**
 * What one package holds, as the quantity per variant id. Comparing contents is how a fulfillment under a retired
 * tracking number is told apart from the live shipment that took the number over.
 */
sealed interface PackageContent {

  /** A variant whose lines add up to nothing is left out, so two listings of the same package compare equal. */
  data class Known(val quantityByVariantId: Map<Long, Int>) : PackageContent

  /** A fulfillment whose lines could not all be read. It equals nothing, so it is replaced when the shipment fits. */
  data object Unknown : PackageContent
}

/** One line of a fulfillment as Shopify lists it; either value may be missing. */
data class PackageLine(val variantId: Long?, val quantity: Int?)

fun packageContentOf(lines: List<PackageLine>): PackageContent {
  val quantityByVariantId = LinkedHashMap<Long, Int>()
  lines.forEach { line ->
    val variantId = line.variantId ?: return PackageContent.Unknown
    val quantity = line.quantity ?: return PackageContent.Unknown
    quantityByVariantId.merge(variantId, quantity, Int::plus)
  }
  return PackageContent.Known(quantityByVariantId.filterValues { it != 0 })
}

/** The request validators keep a shipment's quantities positive, so no variant drops out here. */
fun packageContentOf(shipment: Shipment): PackageContent.Known =
  PackageContent.Known(normalizeShipmentLineItems(shipment).associate { it.productVariantId to it.quantity })

fun PackageContent.isSameAs(other: PackageContent): Boolean =
  this is PackageContent.Known && other is PackageContent.Known && quantityByVariantId == other.quantityByVariantId
