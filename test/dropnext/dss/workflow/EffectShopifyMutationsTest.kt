package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.helper.MutableTimeSource
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


class EffectShopifyMutationsTest {

  @Test
  fun `cancel then create answers both effects in order`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(9000L))
    val cancel = cancelMutation()
    val create = createMutation()
    val report = effectShopifyMutations(fake, listOf(cancel, create), ShipmentSyncBudget.unlimited())
    val expectedEffects = listOf(
      MutationEffect.Canceled(cancel),
      MutationEffect.Created(create, ShopifyFulfillmentId(9000L)),
    )
    assert(report.effects == expectedEffects)
    assert(report.stop == null)
    assert(fake.cancelFulfillmentCalls == listOf("gid://shopify/Fulfillment/8000"))
    assert(fake.createFulfillmentCalls.size == 1)
    assert(fake.orderForDssCalls.isEmpty())
  }

  @Test
  fun `nothing to do answers an empty report`() = runBlocking {
    val report = effectShopifyMutations(FakeShopifyGraphqlService(), emptyList(), ShipmentSyncBudget.unlimited())
    assert(report == MutationEffectReport(effects = emptyList()))
  }

  /** A refusal is about the one fulfillment: the stale one may still be canceled next run, the create has its own room. */
  @Test
  fun `a refused cancel is recorded and the create after it still runs`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.cancelFulfillmentResult = Failure(ShopifyError.UserError(listOf("cannot cancel")))
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(9000L))
    val cancel = cancelMutation()
    val create = createMutation()
    val report = effectShopifyMutations(fake, listOf(cancel, create), ShipmentSyncBudget.unlimited())
    val expectedEffects = listOf(
      MutationEffect.Refused(cancel, listOf("cannot cancel")),
      MutationEffect.Created(create, ShopifyFulfillmentId(9000L)),
    )
    assert(report.effects == expectedEffects)
    assert(report.stop == null)
  }

  @Test
  fun `a cancel that does not reach Shopify stops before any create`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.cancelFulfillmentResult = Failure(ShopifyError.Network("connection reset"))
    val cancel = cancelMutation()
    val report = effectShopifyMutations(fake, listOf(cancel, createMutation()), ShipmentSyncBudget.unlimited())
    assert(report.effects.isEmpty())
    assert(report.stop == MutationStop(cancel, ShopifyError.Network("connection reset")))
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  fun `a refused create is recorded and the next create still runs`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResultQueue.add(Success(ShopifyFulfillmentId(5001L)))
    fake.createFulfillmentResultQueue.add(Failure(ShopifyError.UserError(listOf("tracking number invalid"))))
    fake.createFulfillmentResultQueue.add(Success(ShopifyFulfillmentId(5003L)))
    val first = createMutation(tracking = "TRK-1")
    val second = createMutation(tracking = "TRK-2")
    val third = createMutation(tracking = "TRK-3")
    val report = effectShopifyMutations(fake, listOf(first, second, third), ShipmentSyncBudget.unlimited())
    val expectedEffects = listOf(
      MutationEffect.Created(first, ShopifyFulfillmentId(5001L)),
      MutationEffect.Refused(second, listOf("tracking number invalid")),
      MutationEffect.Created(third, ShopifyFulfillmentId(5003L)),
    )
    assert(report.effects == expectedEffects)
    assert(report.stop == null)
    assert(fake.createFulfillmentCalls.size == 3)
  }

  /** What landed before the failure is kept: the caller reports it, and a retry finds it by its tracking number. */
  @Test
  fun `a create that fails on the wire stops the run and keeps the id created before it`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResultQueue.add(Success(ShopifyFulfillmentId(5001L)))
    fake.createFulfillmentResultQueue.add(Failure(ShopifyError.HttpError(503)))
    val first = createMutation(tracking = "TRK-1")
    val second = createMutation(tracking = "TRK-2")
    val report = effectShopifyMutations(fake, listOf(first, second, createMutation(tracking = "TRK-3")), ShipmentSyncBudget.unlimited())
    assert(report.effects == listOf(MutationEffect.Created(first, ShopifyFulfillmentId(5001L))))
    assert(report.stop == MutationStop(second, ShopifyError.HttpError(503)))
    assert(fake.createFulfillmentCalls.size == 2)
  }

  @Test
  fun `a Graphql error on a create stops the run`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResult = Failure(ShopifyError.GraphqlError("throttled"))
    val first = createMutation(tracking = "TRK-1")
    val report = effectShopifyMutations(fake, listOf(first, createMutation(tracking = "TRK-2")), ShipmentSyncBudget.unlimited())
    assert(report.effects.isEmpty())
    assert(report.stop == MutationStop(first, ShopifyError.GraphqlError("throttled")))
    assert(fake.createFulfillmentCalls.size == 1)
  }

  @Test
  fun `the tracking of a create reaches the service as sent`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(1L))
    effectShopifyMutations(fake, listOf(createMutation(tracking = "TRK-X", trackingUrl = "https://carrier.test/track/TRK-X")), ShipmentSyncBudget.unlimited())
    val call = fake.createFulfillmentCalls.single()
    assert(call.tracking.number == "TRK-X")
    assert(call.tracking.company == "UPS")
    assert(call.tracking.url == "https://carrier.test/track/TRK-X")
    assert(call.lines.single().lineItemId == "gid://shopify/FulfillmentOrderLineItem/401")
    assert(!call.notifyCustomer)
  }

  /** The effect step decides nothing: whether Shopify emails the customer is the plan's call. */
  @Test
  fun `the customer notification of a create reaches the service as planned`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(1L))
    effectShopifyMutations(fake, listOf(createMutation(notifyCustomer = true)), ShipmentSyncBudget.unlimited())
    assert(fake.createFulfillmentCalls.single().notifyCustomer)
  }

  /** The monolith stops waiting soon after; a mutation sent then could land while its retry sends it again. */
  @Test
  fun `a mutation the budget would not hold is not sent, and the stop names none`() = runBlocking {
    val clock = MutableTimeSource()
    val fake = FakeShopifyGraphqlService().apply {
      createFulfillmentResult = Success(ShopifyFulfillmentId(5001L))
      shipmentSyncTimeSource = clock
      shipmentSyncCallTakes = 8.seconds
    }
    val budget = ShipmentSyncBudget.startedAt(clock.markNow(), 25.seconds)
    val first = createMutation(tracking = "TRK-1")
    val second = createMutation(tracking = "TRK-2")

    val report = effectShopifyMutations(fake, listOf(first, second, createMutation(tracking = "TRK-3")), budget)

    assert(report.effects == listOf(MutationEffect.Created(first, ShopifyFulfillmentId(5001L)), MutationEffect.Created(second, ShopifyFulfillmentId(5001L))))
    assert(report.stop == MutationStop(mutation = null, ShopifyError.TimedOut("syncing the shipments", 25.seconds)))
    assert(fake.createFulfillmentCalls.size == 2)
  }

  private fun cancelMutation() =
    ShopifyMutation.FulfillmentCancel(fulfillmentId = ShopifyFulfillmentId(8000L), trackingNumber = "TRK-OLD")

  private fun createMutation(
    tracking: String = "1Z999",
    trackingUrl: String? = null,
    notifyCustomer: Boolean = false,
  ) = ShopifyMutation.FulfillmentCreate(
    lineItems = listOf(
      FulfillmentLine(
        fulfillmentOrderId = "gid://shopify/FulfillmentOrder/301",
        lineItemId = "gid://shopify/FulfillmentOrderLineItem/401",
        quantity = 1,
      ),
    ),
    trackingNumber = tracking,
    carrier = "UPS",
    trackingUrl = trackingUrl,
    notifyCustomer = notifyCustomer,
  )
}
