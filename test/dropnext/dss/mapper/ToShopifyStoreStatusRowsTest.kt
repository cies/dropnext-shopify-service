package dropnext.dss.mapper

import dropnext.dss.contract.ObsoleteShopifyWebhookRow
import dropnext.dss.contract.ShopifyAccessScopesStatus
import dropnext.dss.contract.ShopifyWebhookRegistrationSummary
import dropnext.dss.contract.ShopifyWebhookTopicRow
import dropnext.dss.contract.StaleShopifyWebhookRow
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.ObsoleteWebhookSubscription
import dropnext.dss.domain.ShopifyAccessScope
import dropnext.dss.domain.ShopifyAccessScopeReport
import dropnext.dss.domain.WebhookRegistrationReport
import dropnext.dss.domain.WebhookTopicRegistration
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import org.junit.jupiter.api.Test


/** The domain's reports as the contract's rows: the status vocabulary the monolith translates into badges. */
class ToShopifyStoreStatusRowsTest {

  private val ours = webhookSubscriptionStatus(1, "ORDERS_CREATE", includeFields = listOf("id", "admin_graphql_api_id"))

  private fun rowFor(status: WebhookTopicStatus): ShopifyWebhookTopicRow =
    WebhookRegistrationReport(listOf(WebhookTopicRegistration("ORDERS_CREATE", status)))
      .toShopifyStoreStatusRows()
      .webhooks
      .single()

  @Test
  fun `an active, added or updated topic is named with the subscription's id and address`() {
    val expected = ShopifyWebhookTopicRow(topic = "ORDERS_CREATE", status = "active", id = ours.id, uri = ours.uri)
    assert(rowFor(WebhookTopicStatus.Active(ours)) == expected)
    assert(rowFor(WebhookTopicStatus.Added(ours)) == expected.copy(status = "added"))
    assert(rowFor(WebhookTopicStatus.Updated(ours)) == expected.copy(status = "updated"))
  }

  @Test
  fun `a repointed topic names the address it came from`() {
    val row = rowFor(WebhookTopicStatus.Repointed(ours, previousUri = "https://old.example/webhooks/shopify"))
    assert(row.status == "repointed")
    assert(row.previousUri == "https://old.example/webhooks/shopify")
  }

  @Test
  fun `a mismatched topic is named with both field lists, its filter and its format`() {
    val subscription = webhookSubscriptionStatus(3, "ORDERS_CREATE", filter = "vendor:Acme", format = "XML")
    val expectedFields = listOf("id", "admin_graphql_api_id")
    val expected = ShopifyWebhookTopicRow(
      topic = "ORDERS_CREATE",
      status = "mismatched",
      id = subscription.id,
      uri = subscription.uri,
      includeFields = emptyList(),
      expectedIncludeFields = expectedFields,
      filter = "vendor:Acme",
      format = "XML",
    )
    assert(rowFor(WebhookTopicStatus.Mismatched(subscription, expectedFields)) == expected)
  }

  @Test
  fun `an update Shopify did not apply is named with what it kept`() {
    val row = rowFor(WebhookTopicStatus.NotApplied(webhookSubscriptionStatus(4, "ORDERS_CREATE"), listOf("id")))
    assert(row.status == "not_applied")
    assert(row.includeFields == emptyList<String>())
    assert(row.expectedIncludeFields == listOf("id"))
  }

  /** Shopify's own text stays in the log: the row says only that the topic failed. */
  @Test
  fun `a missing or failed topic carries no subscription`() {
    assert(rowFor(WebhookTopicStatus.Missing) == ShopifyWebhookTopicRow(topic = "ORDERS_CREATE", status = "missing"))
    assert(rowFor(WebhookTopicStatus.Failed("topic: scope missing")) == ShopifyWebhookTopicRow(topic = "ORDERS_CREATE", status = "failed"))
  }

  /** Absent rather than `[]`, as the rows were always answered; the contract reads an absent list as empty. */
  @Test
  fun `stale subscriptions are listed, and left out when there are none`() {
    val stale = webhookSubscriptionStatus(9, "ORDERS_CREATE", uri = "https://old.example/webhooks/shopify")
    val report = WebhookRegistrationReport(
      listOf(
        WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Missing, stale = listOf(stale)),
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Missing),
      ),
    )
    val rows = report.toShopifyStoreStatusRows().webhooks
    assert(rows[0].stale == listOf(StaleShopifyWebhookRow(id = stale.id, uri = "https://old.example/webhooks/shopify")))
    assert(rows[1].stale == null)
  }

  @Test
  fun `an obsolete subscription is obsolete, deleted or delete_failed`() {
    val subscription = webhookSubscriptionStatus(7, "ORDERS_UPDATED")
    val report = WebhookRegistrationReport(
      topics = emptyList(),
      obsolete = listOf(
        ObsoleteWebhookSubscription(subscription, ObsoleteSubscriptionRemoval.NotAttempted),
        ObsoleteWebhookSubscription(subscription, ObsoleteSubscriptionRemoval.Deleted),
        ObsoleteWebhookSubscription(subscription, ObsoleteSubscriptionRemoval.Failed("no answer")),
      ),
    )
    val statuses = report.toShopifyStoreStatusRows().obsoleteWebhooks
    assert(statuses.first() == ObsoleteShopifyWebhookRow(topic = "ORDERS_UPDATED", id = subscription.id, status = "obsolete"))
    assert(statuses.map { it.status } == listOf("obsolete", "deleted", "delete_failed"))
  }

  @Test
  fun `a grant with every scope is complete`() {
    val handles = ShopifyAccessScope.entries.map { it.handle }
    val status = ShopifyAccessScopeReport.from(handles).toShopifyAccessScopesStatus()
    assert(status == ShopifyAccessScopesStatus(status = "complete", granted = handles, missing = emptyList()))
  }

  @Test
  fun `a grant without a scope names it as missing`() {
    val granted = ShopifyAccessScope.entries.drop(1).map { it.handle }
    val status = ShopifyAccessScopeReport.from(granted).toShopifyAccessScopesStatus()
    assert(status.status == "missing")
    assert(status.granted == granted)
    assert(status.missing == listOf(ShopifyAccessScope.entries.first().handle))
  }

  @Test
  fun `the summary counts what the report holds`() {
    val report = WebhookRegistrationReport(
      topics = listOf(
        WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Active(ours), stale = listOf(ours)),
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Added(ours)),
        WebhookTopicRegistration("PRODUCTS_UPDATE", WebhookTopicStatus.Updated(ours)),
        WebhookTopicRegistration("PRODUCTS_DELETE", WebhookTopicStatus.Failed("refused")),
      ),
      obsolete = listOf(
        ObsoleteWebhookSubscription(ours, ObsoleteSubscriptionRemoval.Deleted),
        ObsoleteWebhookSubscription(ours, ObsoleteSubscriptionRemoval.Failed("no answer")),
      ),
    )
    val expected = ShopifyWebhookRegistrationSummary(
      active = 1,
      added = 1,
      updated = 1,
      repointed = 0,
      failed = 1,
      stale = 1,
      deleted = 1,
      obsolete = 1,
    )
    assert(report.toShopifyWebhookRegistrationSummary() == expected)
  }
}
