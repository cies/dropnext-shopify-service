package dropnext.dss.domain

import dropnext.dss.testutil.fixture.EVERY_SCOPE_HANDLE
import dropnext.dss.testutil.fixture.shopInstallReport
import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import org.junit.jupiter.api.Test


private val unknownStore = MonolithPersistOutcome.Failed(httpStatus = 404, detail = "Store not found.", storeNotFound = true)
private val monolithUnreachable = MonolithPersistOutcome.Failed(httpStatus = null, detail = null, storeNotFound = false)
private val failedTopic = WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Failed("denied"))

class ShopInstallOutcomeTest {

  @Test
  fun `a saved token, a complete grant and no failed topic is connected`() {
    assert(shopInstallReport().outcome() == ShopInstallOutcome.Connected)
  }

  @Test
  fun `a missing scope is a gap that names the scope`() {
    val outcome = shopInstallReport(grantedScopeHandles = EVERY_SCOPE_HANDLE - "write_fulfillments").outcome()
    val expected = ShopInstallOutcome.ConnectedWithGaps(
      missingScopes = listOf(ShopifyAccessScope.WRITE_FULFILLMENTS),
      failedTopics = emptyList(),
    )
    assert(outcome == expected)
  }

  @Test
  fun `a failed topic is a gap that names the topic`() {
    val outcome = shopInstallReport(topics = listOf(failedTopic)).outcome()
    assert(outcome == ShopInstallOutcome.ConnectedWithGaps(missingScopes = emptyList(), failedTopics = listOf(failedTopic)))
  }

  /** An update Shopify accepted without applying it is as unsuccessful as a refused one. */
  @Test
  fun `a topic Shopify did not apply is a gap too`() {
    val notApplied = WebhookTopicRegistration(
      "PRODUCTS_CREATE",
      WebhookTopicStatus.NotApplied(webhookSubscriptionStatus(1, "PRODUCTS_CREATE"), expectedIncludeFields = listOf("id")),
    )
    val outcome = shopInstallReport(topics = listOf(notApplied)).outcome()
    assert(outcome == ShopInstallOutcome.ConnectedWithGaps(missingScopes = emptyList(), failedTopics = listOf(notApplied)))
  }

  @Test
  fun `a missing scope and a failed topic at once are both named`() {
    val outcome = shopInstallReport(
      grantedScopeHandles = EVERY_SCOPE_HANDLE - "read_orders",
      topics = listOf(failedTopic),
    ).outcome()
    val expected = ShopInstallOutcome.ConnectedWithGaps(
      missingScopes = listOf(ShopifyAccessScope.READ_ORDERS),
      failedTopics = listOf(failedTopic),
    )
    assert(outcome == expected)
  }

  @Test
  fun `the monolith's own store not found is an unknown store`() {
    assert(shopInstallReport(monolithPersist = unknownStore).outcome() == ShopInstallOutcome.UnknownStore)
  }

  /** A router's `404` means a misconfigured `MONOLITH_BASE_URL`, which needs support rather than a new store. */
  @Test
  fun `a 404 that is not the monolith's store not found is not saved`() {
    val routerNotFound = MonolithPersistOutcome.Failed(httpStatus = 404, detail = null, storeNotFound = false)
    assert(shopInstallReport(monolithPersist = routerNotFound).outcome() == ShopInstallOutcome.NotSaved)
  }

  @Test
  fun `any other refusal of the token is not saved`() {
    val refused = MonolithPersistOutcome.Failed(httpStatus = 503, detail = "upstream timeout", storeNotFound = false)
    assert(shopInstallReport(monolithPersist = refused).outcome() == ShopInstallOutcome.NotSaved)
  }

  @Test
  fun `a monolith that did not answer is not saved`() {
    assert(shopInstallReport(monolithPersist = monolithUnreachable).outcome() == ShopInstallOutcome.NotSaved)
  }

  /** A missing scope cannot be fixed before the store is connected, so the saving decides first. */
  @Test
  fun `an unknown store wins over a missing scope`() {
    val report = shopInstallReport(monolithPersist = unknownStore, grantedScopeHandles = EVERY_SCOPE_HANDLE - "read_orders")
    assert(report.outcome() == ShopInstallOutcome.UnknownStore)
  }

  @Test
  fun `a token that was not saved wins over a failed topic`() {
    val report = shopInstallReport(monolithPersist = monolithUnreachable, topics = listOf(failedTopic))
    assert(report.outcome() == ShopInstallOutcome.NotSaved)
  }

  /** The install did not depend on the count or the shop id lookup, so their failure leaves it connected. */
  @Test
  fun `a failed product count and shop id lookup leave the install connected`() {
    assert(shopInstallReport(productCount = null, shopId = null).outcome() == ShopInstallOutcome.Connected)
  }

  /** The registration reports and handles what points elsewhere or is no longer handled. */
  @Test
  fun `stale and obsolete subscriptions leave the install connected`() {
    val report = shopInstallReport(
      topics = listOf(
        WebhookTopicRegistration(
          "ORDERS_CREATE",
          WebhookTopicStatus.Active(webhookSubscriptionStatus(1, "ORDERS_CREATE")),
          stale = listOf(webhookSubscriptionStatus(2, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify")),
        ),
      ),
      obsolete = listOf(
        ObsoleteWebhookSubscription(
          webhookSubscriptionStatus(3, "ORDERS_UPDATED"),
          ObsoleteSubscriptionRemoval.Failed("denied"),
        ),
      ),
    )
    assert(report.outcome() == ShopInstallOutcome.Connected)
  }
}
