package dropnext.dss.domain

import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import org.junit.jupiter.api.Test


/**
 * The counts the install page and `/api/check` print. The arithmetic is not the risk: which status
 * lands in which bucket is. A topic reported as active when the run failed to register it sends
 * nobody to look at it, and a subscription counted as gone when Shopify refused the deletion keeps
 * delivering.
 */
class WebhookRegistrationReportTest {

  private val subscription = webhookSubscriptionStatus()

  /** One topic in every status a run can end in, so each count has one row to find and several to ignore. */
  private val everyStatus = WebhookRegistrationReport(
    topics = listOf(
      WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Active(subscription)),
      WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Added(subscription)),
      WebhookTopicRegistration("PRODUCTS_UPDATE", WebhookTopicStatus.Updated(subscription)),
      WebhookTopicRegistration("PRODUCTS_DELETE", WebhookTopicStatus.Repointed(subscription, previousUri = "https://old.tunnel/webhooks")),
      WebhookTopicRegistration("ORDERS_UPDATED", WebhookTopicStatus.Missing),
      WebhookTopicRegistration("FULFILLMENTS_CREATE", WebhookTopicStatus.Mismatched(subscription, expectedIncludeFields = listOf("id"))),
      WebhookTopicRegistration("FULFILLMENTS_UPDATE", WebhookTopicStatus.NotApplied(subscription, expectedIncludeFields = listOf("id"))),
      WebhookTopicRegistration("REFUNDS_CREATE", WebhookTopicStatus.Failed("Shopify refused the topic")),
    ),
  )

  @Test
  fun `each count answers for its own status alone`() {
    assert(everyStatus.activeCount == 1)
    assert(everyStatus.addedCount == 1)
    assert(everyStatus.updatedCount == 1)
    assert(everyStatus.repointedCount == 1)
    assert(everyStatus.missingCount == 1)
  }

  /** [WebhookTopicStatus.Unsuccessful] is what a run tried and did not land; a scan's findings are not failures. */
  @Test
  fun `failures are the topics this run tried and left unregistered`() {
    assert(everyStatus.failures.map { it.topic } == listOf("FULFILLMENTS_UPDATE", "REFUNDS_CREATE"))
  }

  /** `Mismatched` and `Missing` are what a read-only scan found, not something a run attempted and lost. */
  @Test
  fun `a scan's mismatched and missing topics are not failures`() {
    val scanned = WebhookRegistrationReport(
      topics = listOf(
        WebhookTopicRegistration("ORDERS_CREATE", WebhookTopicStatus.Mismatched(subscription, expectedIncludeFields = emptyList())),
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Missing),
      ),
    )
    assert(scanned.failures.isEmpty())
    assert(scanned.missingCount == 1)
  }

  /** Stale subscriptions belong to a topic but are counted across the whole report: they are what still delivers elsewhere. */
  @Test
  fun `staleCount sums the stale subscriptions of every topic`() {
    val report = WebhookRegistrationReport(
      topics = listOf(
        WebhookTopicRegistration(
          "ORDERS_CREATE",
          WebhookTopicStatus.Added(subscription),
          stale = listOf(webhookSubscriptionStatus(id = 2, uri = "https://old.tunnel/webhooks"), webhookSubscriptionStatus(id = 3, uri = "https://staging.test/webhooks")),
        ),
        WebhookTopicRegistration("PRODUCTS_CREATE", WebhookTopicStatus.Added(subscription), stale = listOf(webhookSubscriptionStatus(id = 4, uri = "https://old.tunnel/webhooks"))),
        WebhookTopicRegistration("PRODUCTS_UPDATE", WebhookTopicStatus.Active(subscription)),
      ),
    )
    assert(report.staleCount == 3)
  }

  @Test
  fun `a report without stale subscriptions counts none`() {
    assert(everyStatus.staleCount == 0)
  }

  /** A report a scan produced deletes nothing, so the obsolete rows it carries are all still subscribed. */
  @Test
  fun `deletedCount counts what this run removed and obsoleteCount what survived it`() {
    val report = WebhookRegistrationReport(
      topics = emptyList(),
      obsolete = listOf(
        ObsoleteWebhookSubscription(webhookSubscriptionStatus(id = 5, topic = "CARTS_CREATE"), ObsoleteSubscriptionRemoval.Deleted),
        ObsoleteWebhookSubscription(webhookSubscriptionStatus(id = 6, topic = "CARTS_UPDATE"), ObsoleteSubscriptionRemoval.NotAttempted),
        ObsoleteWebhookSubscription(webhookSubscriptionStatus(id = 7, topic = "CHECKOUTS_CREATE"), ObsoleteSubscriptionRemoval.Failed("Shopify refused")),
      ),
    )
    assert(report.deletedCount == 1)
    // Both the one nobody tried to delete and the one Shopify refused keep delivering.
    assert(report.obsoleteCount == 2)
  }

  @Test
  fun `a report carries no obsolete subscriptions unless it is given some`() {
    assert(everyStatus.deletedCount == 0)
    assert(everyStatus.obsoleteCount == 0)
  }

  // ---------- what one subscription says about itself ----------

  @Test
  fun `a subscription without a filter takes every event`() {
    assert(!webhookSubscriptionStatus(filter = null).hasFilter)
  }

  /** Shopify stores a filter that is only whitespace, and it passes every event, exactly as no filter does. */
  @Test
  fun `a blank filter is no filter`() {
    assert(!webhookSubscriptionStatus(filter = "   ").hasFilter)
  }

  @Test
  fun `a subscription with a search expression has a filter`() {
    assert(webhookSubscriptionStatus(filter = "vendor:Acme").hasFilter)
  }

  @Test
  fun `only the JSON format is JSON`() {
    assert(webhookSubscriptionStatus(format = "JSON").isJson)
    assert(!webhookSubscriptionStatus(format = "XML").isJson)
  }
}
