package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import org.junit.jupiter.api.Test


class PackageContentTest {

  @Test
  fun `a variant listed twice is summed`() {
    val content = packageContentOf(listOf(PackageLine(101L, 1), PackageLine(202L, 1), PackageLine(101L, 2)))

    assert(content == PackageContent.Known(mapOf(101L to 3, 202L to 1)))
  }

  @Test
  fun `a variant whose lines add up to nothing is left out`() {
    val content = packageContentOf(listOf(PackageLine(101L, 1), PackageLine(202L, 0)))

    assert(content.isSameAs(PackageContent.Known(mapOf(101L to 1))))
  }

  @Test
  fun `a line without a variant or a quantity makes the content unknown`() {
    assert(packageContentOf(listOf(PackageLine(101L, 1), PackageLine(null, 1))) == PackageContent.Unknown)
    assert(packageContentOf(listOf(PackageLine(101L, null))) == PackageContent.Unknown)
  }

  @Test
  fun `a shipment and a fulfillment of the same package are the same, whatever the order of their lines`() {
    val shipment = Shipment(
      trackingNumber = "T1",
      carrier = null,
      trackingUrl = null,
      lineItems = listOf(ShipmentLineItem(202L, 1), ShipmentLineItem(101L, 1), ShipmentLineItem(101L, 1)),
    )
    val fulfillment = packageContentOf(listOf(PackageLine(101L, 2), PackageLine(202L, 1)))

    assert(packageContentOf(shipment).isSameAs(fulfillment))
    assert(fulfillment.isSameAs(packageContentOf(shipment)))
  }

  @Test
  fun `unknown content is the same as nothing, not even itself`() {
    val known = PackageContent.Known(mapOf(101L to 1))

    assert(!PackageContent.Unknown.isSameAs(PackageContent.Unknown))
    assert(!PackageContent.Unknown.isSameAs(known))
    assert(!known.isSameAs(PackageContent.Unknown))
    assert(!known.isSameAs(PackageContent.Known(mapOf(101L to 2))))
  }
}
