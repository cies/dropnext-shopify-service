package dropnext.dss.domain.fulfillment

import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.minimalOrder
import dropnext.dss.testutil.fixture.orderWithFulfillments
import dropnext.graphql.generated.enums.FulfillmentStatus
import kotlin.test.Test


class LiveFulfillmentsWithTrackingNumberTest {

  @Test
  fun `finds the live fulfillment that carries the tracking number`() {
    val order = orderWithFulfillments(fulfillment(8000L, listOf("TRK-A")), fulfillment(8001L, listOf("TRK-B")))
    assert(liveFulfillmentsWithTrackingNumber(order, "TRK-A").map { it.id } == listOf("gid://shopify/Fulfillment/8000"))
  }

  /** A cancelled fulfillment keeps its tracking number; counting it would make a replaced package impossible to re-create. */
  @Test
  fun `leaves out a cancelled fulfillment that carries the tracking number`() {
    val order = orderWithFulfillments(fulfillment(8000L, listOf("TRK-A"), status = FulfillmentStatus.CANCELLED))
    assert(liveFulfillmentsWithTrackingNumber(order, "TRK-A").isEmpty())
  }

  @Test
  fun `of a cancelled and a live fulfillment with the same tracking number only the live one counts`() {
    val order = orderWithFulfillments(
      fulfillment(8000L, listOf("TRK-A"), status = FulfillmentStatus.CANCELLED),
      fulfillment(8001L, listOf("TRK-A")),
    )
    assert(liveFulfillmentsWithTrackingNumber(order, "TRK-A").map { it.id } == listOf("gid://shopify/Fulfillment/8001"))
  }

  @Test
  fun `answers every live fulfillment when two carry the same tracking number`() {
    val order = orderWithFulfillments(fulfillment(8000L, listOf("TRK-A")), fulfillment(8001L, listOf("TRK-A")))
    assert(liveFulfillmentsWithTrackingNumber(order, "TRK-A").map { it.id } == listOf("gid://shopify/Fulfillment/8000", "gid://shopify/Fulfillment/8001"))
  }

  @Test
  fun `trims both the tracking number asked for and the ones on the fulfillments`() {
    val order = orderWithFulfillments(fulfillment(8000L, listOf(" TRK-A\t")))
    assert(liveFulfillmentsWithTrackingNumber(order, "TRK-A ").size == 1)
  }

  /** Carriers differ on whether case is significant, so a number that differs only in case is another number. */
  @Test
  fun `compares tracking numbers case-sensitively`() {
    val order = orderWithFulfillments(fulfillment(8000L, listOf("trk-a")))
    assert(liveFulfillmentsWithTrackingNumber(order, "TRK-A").isEmpty())
  }

  @Test
  fun `any of a fulfillment's tracking numbers counts`() {
    val order = orderWithFulfillments(fulfillment(8000L, listOf("TRK-A", "TRK-B")))
    assert(liveFulfillmentsWithTrackingNumber(order, "TRK-B").size == 1)
  }

  @Test
  fun `an order without fulfillments has none`() {
    assert(liveFulfillmentsWithTrackingNumber(minimalOrder(), "TRK-A").isEmpty())
  }
}
