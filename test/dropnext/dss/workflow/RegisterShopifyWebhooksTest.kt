package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ObsoleteSubscriptionRemoval
import dropnext.dss.domain.WebhookSubscriptionStatus
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.testutil.fake.FakeShopifyGraphqlService
import dropnext.dss.testutil.fixture.ACME_SHOP
import dropnext.dss.testutil.fixture.webhookSubscriptionStatus
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock


private const val CALLBACK_URL = "https://dss.example/webhooks/shopify"
private const val OLD_CALLBACK_URL = "https://old-tunnel.example/webhooks/shopify"
private const val OLDER_CALLBACK_URL = "https://older-tunnel.example/webhooks/shopify"
private const val PUBSUB_URI = "pubsub://analytics-project:shopify-orders"
private const val REGISTERED_SUBSCRIPTION_ID = "gid://shopify/WebhookSubscription/9999"
private val ALL_TOPICS = setOf("PRODUCTS_CREATE", "PRODUCTS_UPDATE", "PRODUCTS_DELETE", "ORDERS_CREATE")
private val ID_ONLY_FIELDS = listOf("id", "admin_graphql_api_id")


/**
 * One row per handled topic, and Shopify is asked to change only what is not right yet: a reinstall
 * used to re-register everything and show five "already taken" failures beside five active
 * subscriptions. The scan is also what the readiness check answers from, so it is pinned on its own.
 *
 * The policy is what is checked here, against the in-memory service: which topic is registered, updated, repointed or
 * deleted, and what the report says about it. What each of those calls looks like on the wire, and what Shopify's
 * answer decodes into, is `HttpShopifyGraphqlServiceTest`'s.
 */
class RegisterShopifyWebhooksTest {

  private val shopify = FakeShopifyGraphqlService(ACME_SHOP)

  // ---------- registering ----------

  @Test
  fun `a first install registers all four topics and reports each as added`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.registerWebhookCalls.size == 4)
    assert(report.topics.map { it.topic }.toSet() == ALL_TOPICS)
    assert(report.topics.all { it.status is WebhookTopicStatus.Added })
    assert(report.addedCount == 4)
    assert(report.failures.isEmpty())
    val added = report.topics.first().status as WebhookTopicStatus.Added
    assert(added.subscription.id == REGISTERED_SUBSCRIPTION_ID)
    assert(added.subscription.uri == CALLBACK_URL)
  }

  /** Every topic whose resource is loaded through Graphql after the delivery; a deleted product's payload is its id already. */
  @Test
  fun `registers id-only include fields for every topic but products delete`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    registerShopifyWebhooks(shopify, CALLBACK_URL)

    val fieldsByTopic = shopify.registerWebhookCalls.associate { (topic, _, includeFields) -> topic.name to includeFields }
    val expected = mapOf(
      "PRODUCTS_UPDATE" to ID_ONLY_FIELDS,
      "PRODUCTS_CREATE" to ID_ONLY_FIELDS,
      "PRODUCTS_DELETE" to null,
      "ORDERS_CREATE" to ID_ONLY_FIELDS,
    )
    assert(fieldsByTopic == expected)
  }

  /** The reinstall: everything is already there, so nothing is sent and nothing fails. */
  @Test
  fun `a reinstall registers nothing and reports every topic as active`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl())
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.registerWebhookCalls.isEmpty())
    assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
    assert(report.activeCount == 4)
    assert(report.addedCount == 0)
    assert(report.failures.isEmpty())
  }

  @Test
  fun `only the topics missing at our callback url are registered`() = runBlocking {
    stubExistingSubscriptions(listOf(subscription(1, "PRODUCTS_CREATE", CALLBACK_URL), subscription(2, "ORDERS_CREATE", CALLBACK_URL)))
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.registerWebhookCalls.size == 2)
    assert(report.activeCount == 2)
    assert(report.addedCount == 2)
    assert(report.topics.single { it.topic == "PRODUCTS_CREATE" }.status is WebhookTopicStatus.Active)
    assert(report.topics.single { it.topic == "PRODUCTS_DELETE" }.status is WebhookTopicStatus.Added)
  }

  @Test
  fun `a topic Shopify refuses is reported as failed with its user error`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    shopify.registerWebhookResult = Failure(ShopifyError.UserError(listOf("scope missing")))

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(report.failures.size == 4)
    assert(report.failures.all { "scope missing" in (it.status as WebhookTopicStatus.Failed).error })
    assert(report.failures.map { it.topic }.toSet() == ALL_TOPICS)
  }

  /** The install never fails on Shopify's account: without a scan every topic is registered and Shopify sorts it out. */
  @Test
  fun `a failed subscriptions query does not fail the install nor stop the registrations`() = runBlocking {
    stubScanThrottled()
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.registerWebhookCalls.size == 4)
    assert(report.addedCount == 4)
    assert(report.failures.isEmpty())
  }

  // ---------- updating a subscription at our callback url ----------

  /** Registering the topic again is refused for an address already taken; the subscription is changed in place. */
  @Test
  fun `a subscription at our url with the full payload is updated to the declared fields, not registered again`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", CALLBACK_URL, includeFields = emptyList()))
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ID_ONLY_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val updated = shopify.updateWebhookSubscriptionCalls.single()
    assert(updated.subscriptionId == "gid://shopify/WebhookSubscription/1")
    assert(updated.callbackUrl == CALLBACK_URL)
    assert(updated.includeFields == ID_ONLY_FIELDS)
    assert(shopify.registerWebhookCalls.isEmpty())
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }.status as WebhookTopicStatus.Updated
    assert(ordersCreate.subscription.includeFields == ID_ONLY_FIELDS)
    assert(report.updatedCount == 1)
  }

  /** A topic that declares no fields is updated with `null`, the full payload; how that leaves out the variable is the client's business. */
  @Test
  fun `a products delete subscription restricted to some fields is reset to the full payload`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "PRODUCTS_DELETE") + subscription(1, "PRODUCTS_DELETE", CALLBACK_URL, includeFields = listOf("id")))
    stubUpdated(1, "PRODUCTS_DELETE", CALLBACK_URL, includeFields = emptyList())

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.updateWebhookSubscriptionCalls.single().includeFields == null)
    assert(report.topics.single { it.topic == "PRODUCTS_DELETE" }.status is WebhookTopicStatus.Updated)
  }

  /** An update Shopify accepted but did not apply must not read as a repair: it is how a changed meaning of `null` would show. */
  @Test
  fun `an update Shopify accepts without applying it is reported as not applied`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "PRODUCTS_CREATE") + subscription(1, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = listOf("id")))
    stubUpdated(1, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = listOf("id"))

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val productsCreate = report.topics.single { it.topic == "PRODUCTS_CREATE" }.status
    assert((productsCreate as? WebhookTopicStatus.NotApplied)?.answered?.includeFields == listOf("id"))
    assert(report.failures.map { it.topic } == listOf("PRODUCTS_CREATE"))
    assert(report.updatedCount == 0)
  }

  @Test
  fun `an update Shopify refuses is reported as failed with its user error`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", CALLBACK_URL, includeFields = emptyList()))
    stubUpdateRefused("Webhook subscription does not exist")

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }.status as WebhookTopicStatus.Failed
    assert("Webhook subscription does not exist" in ordersCreate.error)
    assert(shopify.registerWebhookCalls.isEmpty())
  }

  /** XML is a body the webhook parsers cannot read, so a subscription at our url in XML is repaired like one with other fields. */
  @Test
  fun `a subscription at our url in xml is updated to json`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", CALLBACK_URL, format = "XML"),
    )
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ID_ONLY_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.updateWebhookSubscriptionCalls.size == 1)
    assert(report.topics.single { it.topic == "ORDERS_CREATE" }.status is WebhookTopicStatus.Updated)
  }

  @Test
  fun `an update Shopify answers with the filter still set is reported as not applied`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "PRODUCTS_UPDATE") + subscription(1, "PRODUCTS_UPDATE", CALLBACK_URL, filter = "vendor:Acme"),
    )
    stubUpdated(1, "PRODUCTS_UPDATE", CALLBACK_URL, includeFields = ID_ONLY_FIELDS, filter = "vendor:Acme")

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val productsUpdate = report.topics.single { it.topic == "PRODUCTS_UPDATE" }.status
    assert((productsUpdate as? WebhookTopicStatus.NotApplied)?.answered?.filter == "vendor:Acme")
  }

  /** Our address is taken by the mismatched subscription, so that one is updated and the stale one stays where it is. */
  @Test
  fun `a mismatched topic is updated in place and keeps its stale subscriptions where they are`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "ORDERS_CREATE") +
        subscription(1, "ORDERS_CREATE", CALLBACK_URL, includeFields = emptyList()) +
        subscription(2, "ORDERS_CREATE", OLD_CALLBACK_URL),
    )
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ID_ONLY_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.updateWebhookSubscriptionCalls.single().subscriptionId == "gid://shopify/WebhookSubscription/1")
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Updated)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
  }

  // ---------- repointing a stale subscription to our callback url ----------

  /** An old address stops receiving copies: the stale subscription itself is repointed, with the topic's fields. */
  @Test
  fun `a missing topic repoints a stale https subscription instead of registering a new one`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL, includeFields = emptyList()))
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ID_ONLY_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val repointed = shopify.updateWebhookSubscriptionCalls.single()
    assert(repointed.subscriptionId == "gid://shopify/WebhookSubscription/1")
    assert(repointed.callbackUrl == CALLBACK_URL)
    assert(repointed.includeFields == ID_ONLY_FIELDS)
    assert(shopify.registerWebhookCalls.isEmpty())
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert((ordersCreate.status as WebhookTopicStatus.Repointed).previousUri == OLD_CALLBACK_URL)
    assert(ordersCreate.stale.isEmpty())
    assert(report.repointedCount == 1)
  }

  /** Shopify allows one subscription per topic and address, so a second one cannot follow the first to ours. */
  @Test
  fun `only the first of several stale subscriptions is repointed, the others stay reported`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "PRODUCTS_UPDATE") +
        subscription(1, "PRODUCTS_UPDATE", OLD_CALLBACK_URL) +
        subscription(2, "PRODUCTS_UPDATE", OLDER_CALLBACK_URL),
    )
    stubUpdated(1, "PRODUCTS_UPDATE", CALLBACK_URL, includeFields = ID_ONLY_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.updateWebhookSubscriptionCalls.single().subscriptionId == "gid://shopify/WebhookSubscription/1")
    val productsUpdate = report.topics.single { it.topic == "PRODUCTS_UPDATE" }
    assert(productsUpdate.status is WebhookTopicStatus.Repointed)
    assert(productsUpdate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
  }

  /** Our address is taken for the topic, so a stale subscription cannot be repointed there; it is reported and left alone. */
  @Test
  fun `a topic subscribed at our url keeps its stale subscriptions where they are`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl() + subscription(1, "PRODUCTS_CREATE", OLD_CALLBACK_URL))

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
    assert(shopify.registerWebhookCalls.isEmpty())
    val productsCreate = report.topics.single { it.topic == "PRODUCTS_CREATE" }
    assert(productsCreate.status is WebhookTopicStatus.Active)
    assert(productsCreate.stale.map { it.uri } == listOf(OLD_CALLBACK_URL))
    assert(report.staleCount == 1)
  }

  /** A Pub/Sub or EventBridge subscription is a pipeline someone set up on purpose, not an earlier address of this service. */
  @Test
  fun `a stale subscription that is not an https address is never repointed`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", PUBSUB_URI))
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
    assert(shopify.registerWebhookCalls.size == 1)
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.uri } == listOf(PUBSUB_URI))
  }

  /** The install is there to leave a subscription at our address; a stale one that could not be repointed must not cost the topic that. */
  @Test
  fun `a repoint Shopify refuses falls back to registering the topic`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL))
    stubUpdateRefused("Webhook subscription does not exist")
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.updateWebhookSubscriptionCalls.size == 1)
    assert(shopify.registerWebhookCalls.size == 1)
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/1"))
  }

  @Test
  fun `a repoint Shopify answers at the old address falls back to registering the topic`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL))
    stubUpdated(1, "ORDERS_CREATE", OLD_CALLBACK_URL, ID_ONLY_FIELDS)
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.registerWebhookCalls.size == 1)
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/1"))
  }

  @Test
  fun `a repoint Shopify answers still in xml falls back to registering the topic`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL, format = "XML"),
    )
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ID_ONLY_FIELDS, format = "XML")
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.registerWebhookCalls.size == 1)
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/1"))
  }

  // ---------- deleting what the service no longer handles ----------

  /** Shops installed while the service subscribed to `orders/updated` keep receiving it until the subscription is deleted. */
  @Test
  fun `a subscription at our url for a topic the service does not handle is deleted`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl() + subscription(1, "ORDERS_UPDATED", CALLBACK_URL))

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.deleteWebhookSubscriptionCalls.single() == "gid://shopify/WebhookSubscription/1")
    assert(report.obsolete.single().removal == ObsoleteSubscriptionRemoval.Deleted)
    assert(report.deletedCount == 1)
    assert(report.obsoleteCount == 0)
    assert(shopify.registerWebhookCalls.isEmpty())
    assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
  }

  /** Another environment or a pipeline someone set up may be what receives it: only our own address is ours to clean. */
  @Test
  fun `a subscription elsewhere for a topic the service does not handle is left alone`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl() + subscription(1, "ORDERS_UPDATED", OLD_CALLBACK_URL))

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.deleteWebhookSubscriptionCalls.isEmpty())
    assert(report.obsolete.isEmpty())
    assert(report.staleCount == 0)
  }

  /** What the run did for the handled topics stands; the subscription that could not be deleted is named. */
  @Test
  fun `a deletion Shopify refuses is reported with its user error and keeps counting as obsolete`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_UPDATED", CALLBACK_URL))
    stubRegisterOk()
    stubDeleteRefused("Webhook subscription does not exist")

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val removal = report.obsolete.single().removal as ObsoleteSubscriptionRemoval.Failed
    assert("Webhook subscription does not exist" in removal.error)
    assert(report.obsoleteCount == 1)
    assert(report.deletedCount == 0)
    assert(report.topics.single { it.topic == "ORDERS_CREATE" }.status is WebhookTopicStatus.Added)
    assert(report.failures.isEmpty())
  }

  // ---------- registering again for a shop installed earlier ----------

  @Test
  fun `registering again does what the install does`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "PRODUCTS_UPDATE") +
        subscription(1, "PRODUCTS_UPDATE", CALLBACK_URL, includeFields = emptyList()) +
        subscription(2, "ORDERS_UPDATED", CALLBACK_URL),
    )
    stubUpdated(1, "PRODUCTS_UPDATE", CALLBACK_URL, ID_ONLY_FIELDS)

    val report = reregisterShopifyWebhooks(shopify, CALLBACK_URL).successValue()

    assert(report.topics.single { it.topic == "PRODUCTS_UPDATE" }.status is WebhookTopicStatus.Updated)
    assert(report.activeCount == 3)
    assert(report.deletedCount == 1)
  }

  /** Without the scan every existing topic would come back refused and nothing would be deleted; the caller can ask again. */
  @Test
  fun `registering again fails on a failed scan and sends Shopify nothing else`() = runBlocking {
    stubScanThrottled()
    stubRegisterOk()

    val result = reregisterShopifyWebhooks(shopify, CALLBACK_URL)

    assert(result.failureReason() is ShopifyError.GraphqlError)
    assert(shopify.webhookSubscriptionsCalls.size == 1)
    assert(shopify.registerWebhookCalls.isEmpty())
    assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
    assert(shopify.deleteWebhookSubscriptionCalls.isEmpty())
  }

  // ---------- scanning ----------

  /**
   * A filter by topic would hide what an earlier version subscribed to, and a filter by address what an earlier tunnel
   * still receives: the scan asks the one primitive that takes neither, once, and sorts the answer itself.
   */
  @Test
  fun `the scan asks Shopify for all of the app's subscriptions without a filter`() = runBlocking {
    stubExistingSubscriptions(emptyList())

    scanShopifyWebhooks(shopify, CALLBACK_URL)

    assert(shopify.webhookSubscriptionsCalls.size == 1)
  }

  @Test
  fun `the scan sorts each topic into active, missing and stale without registering anything`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "PRODUCTS_CREATE", CALLBACK_URL),
        subscription(2, "PRODUCTS_CREATE", OLD_CALLBACK_URL),
        subscription(3, "ORDERS_CREATE", OLD_CALLBACK_URL),
      ),
    )

    val report = scanShopifyWebhooks(shopify, CALLBACK_URL).successValue()

    assert(shopify.registerWebhookCalls.isEmpty())
    val productsCreate = report.topics.single { it.topic == "PRODUCTS_CREATE" }
    assert((productsCreate.status as WebhookTopicStatus.Active).subscription.id == "gid://shopify/WebhookSubscription/1")
    assert(productsCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Missing)
    assert(ordersCreate.stale.map { it.uri } == listOf(OLD_CALLBACK_URL))
    assert(report.missingCount == 3)
    assert(report.staleCount == 2)
  }

  /** Subscribed at our URL is not enough: the payload fields have to be the ones the topic declares, as a set. */
  @Test
  fun `the scan sorts a subscription at our url by its payload fields`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "ORDERS_CREATE", CALLBACK_URL, includeFields = emptyList()),
        subscription(2, "PRODUCTS_DELETE", CALLBACK_URL, includeFields = emptyList()),
        subscription(3, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = ID_ONLY_FIELDS.reversed()),
      ),
    )

    val report = scanShopifyWebhooks(shopify, CALLBACK_URL).successValue()

    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }.status as WebhookTopicStatus.Mismatched
    assert(ordersCreate.subscription.includeFields.isEmpty())
    assert(ordersCreate.expectedIncludeFields == ID_ONLY_FIELDS)
    assert(report.topics.single { it.topic == "PRODUCTS_DELETE" }.status is WebhookTopicStatus.Active)
    assert(report.topics.single { it.topic == "PRODUCTS_CREATE" }.status is WebhookTopicStatus.Active)
    assert(report.topics.count { it.status is WebhookTopicStatus.Mismatched } == 1)
    assert(shopify.updateWebhookSubscriptionCalls.isEmpty())
  }

  /** A filter drops events, and XML is a body the webhook parsers cannot read: either makes a subscription at our url mismatched. */
  @Test
  fun `the scan sorts a subscription at our url with a filter or in xml into mismatched`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "PRODUCTS_UPDATE", CALLBACK_URL, filter = "vendor:Acme"),
        subscription(2, "ORDERS_CREATE", CALLBACK_URL, format = "XML"),
        subscription(3, "PRODUCTS_CREATE", CALLBACK_URL, filter = ""),
      ),
    )

    val report = scanShopifyWebhooks(shopify, CALLBACK_URL).successValue()

    assert(report.topics.single { it.topic == "PRODUCTS_UPDATE" }.status is WebhookTopicStatus.Mismatched)
    assert(report.topics.single { it.topic == "ORDERS_CREATE" }.status is WebhookTopicStatus.Mismatched)
    assert(report.topics.single { it.topic == "PRODUCTS_CREATE" }.status is WebhookTopicStatus.Active)
  }

  /** Only subscriptions differing in something else, such as a filter, can share a topic and address; the declared fields win. */
  @Test
  fun `the scan prefers a subscription at our url with the declared fields over an earlier one without`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "ORDERS_CREATE", CALLBACK_URL, includeFields = emptyList()),
        subscription(2, "ORDERS_CREATE", CALLBACK_URL),
      ),
    )

    val report = scanShopifyWebhooks(shopify, CALLBACK_URL).successValue()

    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }.status
    assert((ordersCreate as? WebhookTopicStatus.Active)?.subscription?.id == "gid://shopify/WebhookSubscription/2")
  }

  /** A read-only scan names the subscription an earlier version left behind and deletes nothing. */
  @Test
  fun `the scan lists a subscription at our url for a topic the service does not handle as obsolete`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "ORDERS_UPDATED", CALLBACK_URL),
        subscription(2, "ORDERS_UPDATED", OLD_CALLBACK_URL),
        subscription(3, "PRODUCTS_CREATE", CALLBACK_URL),
      ),
    )

    val report = scanShopifyWebhooks(shopify, CALLBACK_URL).successValue()

    val obsolete = report.obsolete.single()
    assert(obsolete.subscription.id == "gid://shopify/WebhookSubscription/1")
    assert(obsolete.removal == ObsoleteSubscriptionRemoval.NotAttempted)
    assert(report.obsoleteCount == 1)
    assert(report.topics.none { it.topic == "ORDERS_UPDATED" })
    assert(shopify.deleteWebhookSubscriptionCalls.isEmpty())
  }

  @Test
  fun `a failed scan is a failure the caller can answer from`() = runBlocking {
    stubScanThrottled()
    assert(scanShopifyWebhooks(shopify, CALLBACK_URL) is Failure)
  }

  // ---------- logging ----------

  /** The page shows the registration that followed; the log is where a repoint that did not work is found afterwards. */
  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a repoint Shopify refuses leaves a warn line naming the previous uri`() {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL))
    stubUpdateRefused("Webhook subscription does not exist")
    stubRegisterOk()

    val lines = capturingLogs { runBlocking { registerShopifyWebhooks(shopify, CALLBACK_URL) } }

    val repointFailed = lines.single { "Webhook subscription repoint failed" in it }
    assert(
      repointFailed.startsWith(
        "WARN Webhook subscription repoint failed topic=ORDERS_CREATE id=gid://shopify/WebhookSubscription/1 previousUri=$OLD_CALLBACK_URL error=",
      ),
    )
    assert("Webhook subscription does not exist" in repointFailed)
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `an update Shopify does not apply leaves a warn line with what Shopify kept`() {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "PRODUCTS_CREATE") + subscription(1, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = listOf("id")),
    )
    stubUpdated(1, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = listOf("id"))

    val lines = capturingLogs { runBlocking { registerShopifyWebhooks(shopify, CALLBACK_URL) } }

    val failed = lines.single { "Webhook registration failed" in it }
    assert(
      failed.startsWith(
        "WARN Webhook registration failed topic=PRODUCTS_CREATE error=update_not_applied uri=$CALLBACK_URL includeFields=[id] hasFilter=false format=JSON",
      ),
    )
  }

  @Test
  @ResourceLock(GLOBAL_LOG_REGISTRY)
  fun `a deletion Shopify refuses leaves a warn line naming the subscription`() {
    stubExistingSubscriptions(activeAtCallbackUrl() + subscription(1, "ORDERS_UPDATED", CALLBACK_URL))
    stubDeleteRefused("Webhook subscription does not exist")

    val lines = capturingLogs { runBlocking { registerShopifyWebhooks(shopify, CALLBACK_URL) } }

    val failed = lines.single { "Webhook subscription deletion failed" in it }
    assert(failed.startsWith("WARN Webhook subscription deletion failed topic=ORDERS_UPDATED id=gid://shopify/WebhookSubscription/1 error="))
  }

  // ---------- helpers ----------

  /** A topic the service does not handle declares nothing, so Shopify would report the full payload for it. */
  private fun declaredIncludeFields(topic: String): List<String> =
    ShopifyWebhookTopic.known.singleOrNull { it.subscriptionTopic!!.name == topic }?.includeFields.orEmpty()

  /** The shared fixture with this file's default: a subscription carries the fields its topic declares unless the case is about other ones. */
  private fun subscription(
    id: Int,
    topic: String,
    uri: String,
    includeFields: List<String> = declaredIncludeFields(topic),
    filter: String? = null,
    format: String = "JSON",
  ): WebhookSubscriptionStatus =
    webhookSubscriptionStatus(id = id, topic = topic, uri = uri, includeFields = includeFields, filter = filter, format = format)

  /** Every handled topic but [except] subscribed at our URL with its declared fields, so the test's own row is the only one in question. */
  private fun activeAtCallbackUrl(except: String? = null): List<WebhookSubscriptionStatus> =
    ALL_TOPICS.filter { it != except }.mapIndexed { index, topic -> subscription(100 + index, topic, CALLBACK_URL) }

  private fun stubExistingSubscriptions(subscriptions: List<WebhookSubscriptionStatus>) {
    shopify.webhookSubscriptionsResult = Success(subscriptions)
  }

  /** Throttling is what a scan realistically fails with, and it is retryable, so the caller may ask again. */
  private fun stubScanThrottled() {
    shopify.webhookSubscriptionsResult = Failure(ShopifyError.GraphqlError("Throttled", codes = listOf("THROTTLED")))
  }

  private fun stubRegisterOk() {
    shopify.registerWebhookResult = Success(REGISTERED_SUBSCRIPTION_ID)
  }

  private fun stubUpdated(
    id: Int,
    topic: String,
    uri: String,
    includeFields: List<String>,
    filter: String? = null,
    format: String = "JSON",
  ) {
    shopify.updateWebhookSubscriptionResult = Success(subscription(id, topic, uri, includeFields, filter, format))
  }

  private fun stubUpdateRefused(message: String) {
    shopify.updateWebhookSubscriptionResult = Failure(ShopifyError.UserError(listOf(message)))
  }

  private fun stubDeleteRefused(message: String) {
    shopify.deleteWebhookSubscriptionResult = Failure(ShopifyError.UserError(listOf(message)))
  }
}
