package dropnext.dss.domain.fulfillment

import dropnext.dss.contract.Shipment
import dropnext.dss.contract.ShipmentLineItem
import dropnext.dss.testutil.fixture.fulfillment
import dropnext.dss.testutil.fixture.openFulfillmentOrder
import dropnext.dss.testutil.fixture.orderWithFulfillmentOrders
import dropnext.graphql.generated.enums.FulfillmentOrderStatus
import dropnext.graphql.generated.enums.FulfillmentStatus
import dropnext.graphql.generated.getorderfordss.Fulfillment
import dropnext.graphql.generated.getorderfordss.Order
import org.junit.jupiter.api.Test


private const val X = 101L
private const val Y = 202L

class PlanCancelsTest {

  /** An order for [X] and [Y] whose fulfillment orders have [openX] and [openY] left, carrying [fulfillments]. */
  private fun order(vararg fulfillments: Fulfillment, openX: Int = 0, openY: Int = 0): Order =
    orderWithFulfillmentOrders(
      openFulfillmentOrder(X, openX, foId = 301L, lineItemId = 401L, status = openStatus(openX)),
      openFulfillmentOrder(Y, openY, foId = 302L, lineItemId = 402L, status = openStatus(openY)),
    ).copy(fulfillments = fulfillments.toList())

  private fun openStatus(open: Int) = if (open > 0) FulfillmentOrderStatus.OPEN else FulfillmentOrderStatus.CLOSED

  private fun shipment(trackingNumber: String, vararg lines: Pair<Long, Int>) =
    Shipment(trackingNumber, carrier = null, trackingUrl = null, lineItems = lines.map { (variantId, quantity) -> ShipmentLineItem(variantId, quantity) })

  private fun content(vararg lines: Pair<Long, Int>) = PackageContent.Known(mapOf(*lines))

  private val f1 = fulfillment(8001L, listOf("T1"))
  private val f2 = fulfillment(8002L, listOf("T2"))

  private fun CancelPlan.canceled() = cancels.map { it.fulfillment.id to it.reason }

  // ---------- which fulfillments are decided ----------

  @Test
  fun `a fulfillment under a retired number no live shipment has is canceled`() {
    val plan = planCancels(order(f1), shipments = emptyList(), retiredTrackingNumbers = listOf("T1"), contents = emptyMap())

    assert(plan.canceled() == listOf(f1.id to CancelReason.RETIRED))
    assert(plan.cancels.single().trackingNumber == "T1")
  }

  /** The split from the design: F1 held 2 × X and 1 × Y under T1, and B keeps T1 with the X only. */
  @Test
  fun `a fulfillment whose live shipment has another content is canceled, as its replacement fits`() {
    val b = shipment("T1", X to 2)
    val c = shipment("T2", Y to 1)

    val plan = planCancels(order(f1), listOf(b, c), listOf("T1"), mapOf(f1.id to content(X to 2, Y to 1)))

    assert(plan.canceled() == listOf(f1.id to CancelReason.CONTENT_CHANGED))
    assert(plan.keptStale.isEmpty())
  }

  @Test
  fun `a fulfillment whose live shipment has exactly its content is kept`() {
    val b = shipment("T1", X to 1, Y to 1, X to 1)

    val plan = planCancels(order(f1), listOf(b), listOf("T1"), mapOf(f1.id to content(Y to 1, X to 2)))

    assert(plan.cancels.isEmpty())
    assert(plan.keptStale.isEmpty())
  }

  @Test
  fun `a retired number only on a canceled fulfillment, or on none, plans nothing`() {
    val canceled = fulfillment(8001L, listOf("T1"), status = FulfillmentStatus.CANCELLED)

    val plan = planCancels(order(canceled), listOf(shipment("T3", X to 1)), listOf("T1", "T2"), emptyMap())

    assert(plan == CancelPlan(emptyList(), emptyList(), emptyList()))
  }

  @Test
  fun `two live fulfillments under one retired number are each decided on their own`() {
    val first = fulfillment(8001L, listOf("T1"))
    val second = fulfillment(8002L, listOf("T1"))

    val plan = planCancels(order(first, second), emptyList(), listOf("T1"), emptyMap())

    assert(plan.canceled() == listOf(first.id to CancelReason.RETIRED, second.id to CancelReason.RETIRED))
  }

  /** The shipment is in Shopify through the matching one, so the other is canceled without asking for room. */
  @Test
  fun `of two live fulfillments under a reused number the one with other content is canceled`() {
    val matching = fulfillment(8001L, listOf("T1"))
    val stale = fulfillment(8002L, listOf("T1"))
    val b = shipment("T1", X to 2)
    val contents = mapOf(matching.id to content(X to 2), stale.id to content(X to 1))

    val plan = planCancels(order(matching, stale), listOf(b), listOf("T1"), contents)

    assert(plan.canceled() == listOf(stale.id to CancelReason.CONTENT_CHANGED))
    assert(plan.keptStale.isEmpty())
  }

  @Test
  fun `a fulfillment that also carries the number of an unretired live shipment is kept`() {
    val shared = fulfillment(8001L, listOf("T1", " T3 "))
    val d = shipment("T3", X to 1)

    val plan = planCancels(order(shared), listOf(d), listOf("T1"), emptyMap())

    assert(plan.cancels.isEmpty())
    val expected = KeptSharedFulfillment(shared, "T1", listOf("T3"), KeptSharedReason.CARRIES_LIVE_TRACKING_NUMBER)
    assert(plan.keptShared == listOf(expected))
  }

  @Test
  fun `a fulfillment under two retired numbers is decided once, and kept when one of them matches`() {
    val both = fulfillment(8001L, listOf("T2", "T1"))
    val b = shipment("T1", X to 1)

    val plan = planCancels(order(both), listOf(b), listOf("T2", "T1"), mapOf(both.id to content(X to 1)))

    assert(plan.cancels.isEmpty())
  }

  @Test
  fun `retired numbers are compared trimmed`() {
    val plan = planCancels(order(fulfillment(8001L, listOf(" T1"))), emptyList(), listOf("T1 "), emptyMap())

    assert(plan.cancels.single().trackingNumber == "T1")
  }

  /** Shopify refused its cancel, or no longer has it: either way it stays live, and its shipment waits for nothing. */
  @Test
  fun `a fulfillment this run cannot cancel is not planned and frees no room`() {
    val b = shipment("T1", X to 2)
    val contents = mapOf(f1.id to content(X to 1), f2.id to content(X to 1))

    val plan = planCancels(order(f1, f2), listOf(b), listOf("T1", "T2"), contents, setOf(f2.id))

    assert(plan.cancels.isEmpty())
    assert(plan.keptStale == listOf(KeptStaleFulfillment(f1, "T1", listOf(ShortLine(X, asked = 2, available = 1)))))
  }

  /** Only a fulfillment made by hand carries two numbers; canceling it would guess which package it holds. */
  @Test
  fun `a fulfillment under two numbers that live shipments took over is kept and reported`() {
    val both = fulfillment(8001L, listOf("T1", "T2"))
    val b1 = shipment("T1", X to 1)
    val b2 = shipment("T2", Y to 1)

    val plan = planCancels(order(both), listOf(b1, b2), listOf("T1", "T2"), mapOf(both.id to content(X to 5)))

    assert(plan.cancels.isEmpty())
    assert(plan.keptStale.isEmpty())
    val expected = KeptSharedFulfillment(both, "T1", listOf("T1", "T2"), KeptSharedReason.CARRIES_TWO_REUSED_TRACKING_NUMBERS)
    assert(plan.keptShared == listOf(expected))
  }

  /** A fulfillment of more lines than one page, or with a line whose variant was deleted. */
  @Test
  fun `a fulfillment of unknown content is a content-changed cancel, and gives back no room`() {
    val b = shipment("T1", X to 1)

    val fits = planCancels(order(f1, openX = 1), listOf(b), listOf("T1"), mapOf(f1.id to PackageContent.Unknown))
    val doesNotFit = planCancels(order(f1), listOf(b), listOf("T1"), mapOf(f1.id to PackageContent.Unknown))

    assert(fits.canceled() == listOf(f1.id to CancelReason.CONTENT_CHANGED))
    assert(doesNotFit.cancels.isEmpty())
    assert(doesNotFit.keptStale.single().shortLines == listOf(ShortLine(X, asked = 1, available = 0)))
  }

  // ---------- replacements must fit ----------

  /** The split moved more units under T1 than the order has: the stale fulfillment keeps its tracking events. */
  @Test
  fun `a replacement that does not fit keeps its stale fulfillment`() {
    val b = shipment("T1", X to 3)

    val plan = planCancels(order(f1, openX = 1), listOf(b), listOf("T1"), mapOf(f1.id to content(X to 1)))

    assert(plan.cancels.isEmpty())
    assert(plan.keptStale == listOf(KeptStaleFulfillment(f1, "T1", listOf(ShortLine(X, asked = 3, available = 2)))))
  }

  @Test
  fun `a replacement for a variant not on the order does not fit`() {
    val b = shipment("T1", 999L to 1)

    val plan = planCancels(order(f1), listOf(b), listOf("T1"), mapOf(f1.id to content(X to 1)))

    assert(plan.keptStale.single().shortLines == listOf(ShortLine(999L, asked = 1, available = 0)))
  }

  @Test
  fun `a retired fulfillment's content counts as room for a replacement`() {
    val b = shipment("T1", X to 1, Y to 1)

    val plan = planCancels(order(f1, f2), listOf(b), listOf("T1", "T2"), mapOf(f1.id to content(X to 1), f2.id to content(Y to 1)))

    assert(plan.canceled() == listOf(f1.id to CancelReason.CONTENT_CHANGED, f2.id to CancelReason.RETIRED))
  }

  @Test
  fun `two replacements share the room, the first in request order first`() {
    val b1 = shipment("T1", X to 2)
    val b2 = shipment("T2", X to 2)
    val contents = mapOf(f1.id to content(X to 1), f2.id to content(X to 1))

    val plan = planCancels(order(f1, f2, openX = 1), listOf(b1, b2), listOf("T1", "T2"), contents)

    assert(plan.canceled() == listOf(f1.id to CancelReason.CONTENT_CHANGED))
    assert(plan.keptStale == listOf(KeptStaleFulfillment(f2, "T2", listOf(ShortLine(X, asked = 2, available = 1)))))
  }

  /** B2 fits only with the Y that F1 gives back, and F1 stays because B1 does not fit. */
  @Test
  fun `a dropped cancel can make another replacement not fit`() {
    val b1 = shipment("T1", X to 3)
    val b2 = shipment("T2", Y to 2)
    val contents = mapOf(f1.id to content(X to 1, Y to 1), f2.id to content(Y to 1))

    val plan = planCancels(order(f1, f2), listOf(b1, b2), listOf("T1", "T2"), contents)

    assert(plan.cancels.isEmpty())
    assert(plan.keptStale.map { it.fulfillment.id to it.shortLines } == listOf(
      f1.id to listOf(ShortLine(X, asked = 3, available = 1)),
      f2.id to listOf(ShortLine(Y, asked = 2, available = 1)),
    ))
  }

  // ---------- which contents are loaded ----------

  @Test
  fun `only a run that reuses a retired number loads contents`() {
    val gone = fulfillment(8003L, listOf("T3"))
    val canceled = fulfillment(8004L, listOf("T1"), status = FulfillmentStatus.CANCELLED)
    val b = shipment("T1", X to 1)

    val reused = fulfillmentsToCompare(order(f1, gone, canceled), listOf(b), listOf("T3", "T1"))
    val notReused = fulfillmentsToCompare(order(f1, gone), listOf(shipment("T2", X to 1)), listOf("T3", "T1"))
    val onNoLiveFulfillment = fulfillmentsToCompare(order(canceled, gone), listOf(b), listOf("T3", "T1"))

    assert(reused.map { it.id } == listOf(f1.id, gone.id))
    assert(notReused.isEmpty())
    assert(onNoLiveFulfillment.isEmpty())
  }
}
