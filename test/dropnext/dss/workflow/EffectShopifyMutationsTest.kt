package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dev.forkhandles.result4k.valueOrNull
import dropnext.dss.domain.ShopifyFulfillmentId
import dropnext.dss.lib.shopify.graphql.FulfillmentLine
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.helper.failureReason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


class EffectShopifyMutationsTest {

  @Test
  fun `cancel then create answers the created id`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(9000L))
    val mutations = listOf(
      ShopifyMutation.FulfillmentCancel("gid://shopify/Fulfillment/8000"),
      createMutation(),
    )
    val result = effectShopifyMutations(fake, mutations)
    assert(result is Success)
    assert(result.valueOrNull() == listOf(ShopifyFulfillmentId(9000L)))
    assert(fake.cancelFulfillmentCalls == listOf("gid://shopify/Fulfillment/8000"))
    assert(fake.createFulfillmentCalls.size == 1)
    assert(fake.orderForDssCalls.isEmpty())
  }

  @Test
  fun `a failed cancel stops before any create`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.cancelFulfillmentResult = Failure(ShopifyError.UserError(listOf("cannot cancel")))
    val result = effectShopifyMutations(
      fake,
      listOf(ShopifyMutation.FulfillmentCancel("gid://shopify/Fulfillment/8000"), createMutation()),
    )
    assert(result is Failure)
    assert(fake.createFulfillmentCalls.isEmpty())
  }

  @Test
  fun `first create error stops later creates`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResultQueue.add(Success(ShopifyFulfillmentId(5001L)))
    fake.createFulfillmentResultQueue.add(Failure(ShopifyError.UserError(listOf("tracking number invalid"))))
    val result = effectShopifyMutations(
      fake,
      listOf(
        createMutation(tracking = "TRK-1"),
        createMutation(tracking = "TRK-2"),
        createMutation(tracking = "TRK-3"),
      ),
    )
    assert(result is Failure)
    assert(result.failureReason() is ShopifyError.UserError)
    assert(fake.createFulfillmentCalls.size == 2)
  }

  @Test
  fun `Graphql error on create is returned and later mutations are not run`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResult = Failure(ShopifyError.GraphqlError("throttled"))
    val result = effectShopifyMutations(
      fake,
      listOf(
        createMutation(tracking = "TRK-1"),
        createMutation(tracking = "TRK-2"),
      ),
    )
    assert(result is Failure)
    assert(result.failureReason() is ShopifyError.GraphqlError)
    assert(fake.createFulfillmentCalls.size == 1)
  }

  @Test
  fun `the tracking of a create reaches the service as sent`() = runBlocking {
    val fake = FakeShopifyGraphqlService()
    fake.createFulfillmentResult = Success(ShopifyFulfillmentId(1L))
    effectShopifyMutations(fake, listOf(createMutation(tracking = "TRK-X", trackingUrl = "https://carrier.test/track/TRK-X")))
    val call = fake.createFulfillmentCalls.single()
    assert(call.tracking.number == "TRK-X")
    assert(call.tracking.company == "UPS")
    assert(call.tracking.url == "https://carrier.test/track/TRK-X")
    assert(call.lines.single().lineItemId == "gid://shopify/FulfillmentOrderLineItem/401")
    // The retailer's own store tells its customers; a `true` here would have Shopify email them on our behalf.
    assert(!call.notifyCustomer)
  }

  private fun createMutation(tracking: String = "1Z999", trackingUrl: String? = null) = ShopifyMutation.FulfillmentCreate(
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
    notifyCustomer = false,
  )
}
