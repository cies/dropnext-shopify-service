package dropnext.dss.lib.shopify.graphql

import dropnext.graphql.generated.enums.FulfillmentEventStatus
import org.junit.jupiter.api.Test


class ParsedFulfillmentStatusTest {

  @Test
  fun `maps in_transit synonyms`() {
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("in_transit") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("IN-TRANSIT") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("in transit") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("transit") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.IN_TRANSIT)
  }

  @Test
  fun `maps delivery synonyms`() {
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("delivered") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.DELIVERED)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("delivery") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.DELIVERED)
  }

  @Test
  fun `maps out for delivery`() {
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("out_for_delivery") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.OUT_FOR_DELIVERY)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("out for delivery") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.OUT_FOR_DELIVERY)
  }

  @Test
  fun `maps confirmation synonyms`() {
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("confirmed") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.CONFIRMED)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("pending") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.CONFIRMED)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("info_received") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.CONFIRMED)
  }

  @Test
  fun `maps failure synonyms`() {
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("failure") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.FAILURE)
    assert((ParsedFulfillmentStatus.parseFulfillmentEventStatus("failed") as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.FAILURE)
  }

  @Test
  fun `unknown status preserves the raw input`() {
    val result = ParsedFulfillmentStatus.parseFulfillmentEventStatus("yeeted")
    assert(result is ParsedFulfillmentStatus.Unknown)
    assert((result as ParsedFulfillmentStatus.Unknown).raw == "yeeted")
  }

  @Test
  fun `accepts canonical enum names directly`() {
    val result = ParsedFulfillmentStatus.parseFulfillmentEventStatus("READY_FOR_PICKUP")
    assert(result is ParsedFulfillmentStatus.Known)
    assert((result as ParsedFulfillmentStatus.Known).value == FulfillmentEventStatus.READY_FOR_PICKUP)
  }
}
