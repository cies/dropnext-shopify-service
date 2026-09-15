package dropnext.dss.workflow

import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopDomain
import dropnext.dss.domain.ShopifyAdminToken
import dropnext.dss.domain.WebhookTopicStatus
import dropnext.dss.lib.shopify.graphql.HttpShopifyGraphqlService
import dropnext.dss.lib.shopify.graphql.ShopifyGraphqlService
import dropnext.dss.lib.shopify.webhook.ShopifyWebhookTopic
import dropnext.dss.testutil.fake.FakeShopifyGraphqlServer
import dropnext.dss.testutil.helper.GLOBAL_LOG_REGISTRY
import dropnext.dss.testutil.helper.capturingLogs
import dropnext.dss.testutil.helper.shopifyGraphqlUrl
import dropnext.dss.testutil.helper.testHttpClient
import dropnext.graphql.generated.GetWebhookSubscriptions
import dropnext.graphql.generated.RegisterWebhook
import dropnext.graphql.generated.UpdateWebhookSubscription
import dropnext.graphql.generated.enums.WebhookSubscriptionFormat
import dropnext.graphql.generated.enums.WebhookSubscriptionTopic
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscription as ExistingSubscription
import dropnext.graphql.generated.getwebhooksubscriptions.WebhookSubscriptionConnection
import dropnext.graphql.generated.registerwebhook.UserError as RegisterUserError
import dropnext.graphql.generated.registerwebhook.WebhookSubscription as NewSubscription
import dropnext.graphql.generated.registerwebhook.WebhookSubscriptionCreatePayload
import dropnext.graphql.generated.updatewebhooksubscription.UserError as UpdateUserError
import dropnext.graphql.generated.updatewebhooksubscription.WebhookSubscription as UpdatedSubscription
import dropnext.graphql.generated.updatewebhooksubscription.WebhookSubscriptionUpdatePayload
import io.ktor.client.HttpClient
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.parallel.ResourceLock


private const val CALLBACK_URL = "https://dss.example/webhooks/shopify"
private const val OLD_CALLBACK_URL = "https://old-tunnel.example/webhooks/shopify"
private const val OLDER_CALLBACK_URL = "https://older-tunnel.example/webhooks/shopify"
private const val PUBSUB_URI = "pubsub://analytics-project:shopify-orders"
private val ALL_TOPICS = setOf("PRODUCTS_CREATE", "PRODUCTS_UPDATE", "PRODUCTS_DELETE", "ORDERS_CREATE", "ORDERS_UPDATED")
private val ORDERS_FIELDS = listOf("id", "admin_graphql_api_id")


/**
 * One row per handled topic, and Shopify is asked to change only what is not right yet: a reinstall
 * used to re-register everything and show five "already taken" failures beside five active
 * subscriptions. The scan is also what the readiness check answers from, so it is pinned on its own.
 */
class RegisterShopifyWebhooksTest {

  private lateinit var fake: FakeShopifyGraphqlServer
  private lateinit var httpClient: HttpClient
  private lateinit var shopify: ShopifyGraphqlService

  @BeforeTest
  fun setUp() {
    fake = FakeShopifyGraphqlServer()
    val port = fake.start()
    httpClient = testHttpClient()
    val url = URI(shopifyGraphqlUrl(port)).toURL()
    val gqlClient = GraphQLKtorClient(url, httpClient)
    shopify = HttpShopifyGraphqlService(ShopDomain.parse("acme.myshopify.com")!!, gqlClient, ShopifyAdminToken("tok"))
  }

  @AfterTest
  fun tearDown() {
    httpClient.close()
    fake.stop()
  }

  // ---------- registering ----------

  @Test
  fun `a first install registers all five topics and reports each as added`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 5)
    assert(report.topics.map { it.topic }.toSet() == ALL_TOPICS)
    assert(report.topics.all { it.status is WebhookTopicStatus.Added })
    assert(report.addedCount == 5)
    assert(report.failures.isEmpty())
    val added = report.topics.first().status as WebhookTopicStatus.Added
    assert(added.subscription.id == "gid://shopify/WebhookSubscription/9999")
    assert(added.subscription.uri == CALLBACK_URL)
  }

  @Test
  fun `restricts orders topics to id-only include fields`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    stubRegisterOk()

    registerShopifyWebhooks(shopify, CALLBACK_URL)

    val registers = fake.calls.filter { it.operationName == "RegisterWebhook" }
    val ordersCalls = registers.filter { "ORDERS_CREATE" in it.rawBody || "ORDERS_UPDATED" in it.rawBody }
    val productCalls = registers.filter { call ->
      "PRODUCTS_CREATE" in call.rawBody || "PRODUCTS_UPDATE" in call.rawBody || "PRODUCTS_DELETE" in call.rawBody
    }
    assert(ordersCalls.size == 2)
    assert(productCalls.size == 3)
    ordersCalls.forEach { call -> assert("admin_graphql_api_id" in call.rawBody) }
    productCalls.forEach { call -> assert("admin_graphql_api_id" !in call.rawBody) }
  }

  /** The reinstall: everything is already there, so nothing is sent and nothing fails. */
  @Test
  fun `a reinstall registers nothing and reports every topic as active`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl())
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
    assert(updateCalls().isEmpty())
    assert(report.activeCount == 5)
    assert(report.addedCount == 0)
    assert(report.failures.isEmpty())
  }

  @Test
  fun `only the topics missing at our callback url are registered`() = runBlocking {
    stubExistingSubscriptions(listOf(subscription(1, "PRODUCTS_CREATE", CALLBACK_URL), subscription(2, "ORDERS_CREATE", CALLBACK_URL)))
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 3)
    assert(report.activeCount == 2)
    assert(report.addedCount == 3)
    assert(report.topics.single { it.topic == "PRODUCTS_CREATE" }.status is WebhookTopicStatus.Active)
    assert(report.topics.single { it.topic == "PRODUCTS_DELETE" }.status is WebhookTopicStatus.Added)
  }

  @Test
  fun `a topic Shopify refuses is reported as failed with its user error`() = runBlocking {
    stubExistingSubscriptions(emptyList())
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = listOf(RegisterUserError(field = listOf("topic"), message = "scope missing")),
          webhookSubscription = null,
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(report.failures.size == 5)
    assert(report.failures.all { "scope missing" in (it.status as WebhookTopicStatus.Failed).error })
    assert(report.failures.map { it.topic }.toSet() == ALL_TOPICS)
  }

  /** The install never fails on Shopify's account: without a scan every topic is registered and Shopify sorts it out. */
  @Test
  fun `a failed subscriptions query does not fail the install nor stop the registrations`() = runBlocking {
    fake.stubRaw("GetWebhookSubscriptions", """{"data":null,"errors":[{"message":"Throttled"}]}""")
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 5)
    assert(report.addedCount == 5)
    assert(report.failures.isEmpty())
  }

  // ---------- updating a subscription at our callback url ----------

  /** Registering the topic again is refused for an address already taken; the subscription is changed in place. */
  @Test
  fun `a subscription at our url with the full payload is updated to the declared fields, not registered again`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", CALLBACK_URL, includeFields = emptyList()))
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ORDERS_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val variables = updateCalls().single().variables.jsonObject
    assert(variables["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/1")
    assert(variables["uri"]!!.jsonPrimitive.content == CALLBACK_URL)
    assert(variables["includeFields"]!!.jsonArray.map { it.jsonPrimitive.content } == ORDERS_FIELDS)
    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }.status as WebhookTopicStatus.Updated
    assert(ordersCreate.subscription.includeFields == ORDERS_FIELDS)
    assert(report.updatedCount == 1)
  }

  /** `null` is the full payload: the client leaves the variable out, and the operation's `= null` default resets the fields. */
  @Test
  fun `a products subscription restricted to some fields is reset to the full payload`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "PRODUCTS_CREATE") + subscription(1, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = listOf("id")))
    stubUpdated(1, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = emptyList())

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert("includeFields" !in updateCalls().single().variables.jsonObject)
    assert(report.topics.single { it.topic == "PRODUCTS_CREATE" }.status is WebhookTopicStatus.Updated)
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
    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
  }

  /** XML is a body the webhook parsers cannot read, so a subscription at our url in XML is repaired like one with other fields. */
  @Test
  fun `a subscription at our url in xml is updated to json`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", CALLBACK_URL, format = WebhookSubscriptionFormat.XML),
    )
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ORDERS_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(updateCalls().size == 1)
    assert(report.topics.single { it.topic == "ORDERS_CREATE" }.status is WebhookTopicStatus.Updated)
  }

  @Test
  fun `an update Shopify answers with the filter still set is reported as not applied`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "PRODUCTS_UPDATE") + subscription(1, "PRODUCTS_UPDATE", CALLBACK_URL, filter = "vendor:Acme"),
    )
    stubUpdated(1, "PRODUCTS_UPDATE", CALLBACK_URL, includeFields = emptyList(), filter = "vendor:Acme")

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
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ORDERS_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(updateCalls().single().variables.jsonObject["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/1")
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Updated)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
  }

  // ---------- repointing a stale subscription to our callback url ----------

  /** An old address stops receiving copies: the stale subscription itself is repointed, with the topic's fields. */
  @Test
  fun `a missing topic repoints a stale https subscription instead of registering a new one`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL, includeFields = emptyList()))
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ORDERS_FIELDS)

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    val variables = updateCalls().single().variables.jsonObject
    assert(variables["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/1")
    assert(variables["uri"]!!.jsonPrimitive.content == CALLBACK_URL)
    assert(variables["includeFields"]!!.jsonArray.map { it.jsonPrimitive.content } == ORDERS_FIELDS)
    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
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
    stubUpdated(1, "PRODUCTS_UPDATE", CALLBACK_URL, includeFields = emptyList())

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(updateCalls().single().variables.jsonObject["id"]!!.jsonPrimitive.content == "gid://shopify/WebhookSubscription/1")
    val productsUpdate = report.topics.single { it.topic == "PRODUCTS_UPDATE" }
    assert(productsUpdate.status is WebhookTopicStatus.Repointed)
    assert(productsUpdate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
  }

  /** Our address is taken for the topic, so a stale subscription cannot be repointed there; it is reported and left alone. */
  @Test
  fun `a topic subscribed at our url keeps its stale subscriptions where they are`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl() + subscription(1, "PRODUCTS_CREATE", OLD_CALLBACK_URL))

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(updateCalls().isEmpty())
    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
    val productsCreate = report.topics.single { it.topic == "PRODUCTS_CREATE" }
    assert(productsCreate.status is WebhookTopicStatus.Active)
    assert(productsCreate.stale.map { it.uri } == listOf(OLD_CALLBACK_URL))
    assert(report.staleCount == 1)
  }

  /** A Pub/Sub or EventBridge subscription is a pipeline someone set up on purpose, not an earlier address of this service. */
  @Test
  fun `a stale subscription that is not an https address is never repointed`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_UPDATED") + subscription(1, "ORDERS_UPDATED", PUBSUB_URI))
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(updateCalls().isEmpty())
    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 1)
    val ordersUpdated = report.topics.single { it.topic == "ORDERS_UPDATED" }
    assert(ordersUpdated.status is WebhookTopicStatus.Added)
    assert(ordersUpdated.stale.map { it.uri } == listOf(PUBSUB_URI))
  }

  /** The install is there to leave a subscription at our address; a stale one that could not be repointed must not cost the topic that. */
  @Test
  fun `a repoint Shopify refuses falls back to registering the topic`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL))
    stubUpdateRefused("Webhook subscription does not exist")
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(updateCalls().size == 1)
    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 1)
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/1"))
  }

  @Test
  fun `a repoint Shopify answers at the old address falls back to registering the topic`() = runBlocking {
    stubExistingSubscriptions(activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL))
    stubUpdated(1, "ORDERS_CREATE", OLD_CALLBACK_URL, ORDERS_FIELDS)
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 1)
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/1"))
  }

  @Test
  fun `a repoint Shopify answers still in xml falls back to registering the topic`() = runBlocking {
    stubExistingSubscriptions(
      activeAtCallbackUrl(except = "ORDERS_CREATE") + subscription(1, "ORDERS_CREATE", OLD_CALLBACK_URL, format = WebhookSubscriptionFormat.XML),
    )
    stubUpdated(1, "ORDERS_CREATE", CALLBACK_URL, ORDERS_FIELDS, format = WebhookSubscriptionFormat.XML)
    stubRegisterOk()

    val report = registerShopifyWebhooks(shopify, CALLBACK_URL)

    assert(fake.calls.count { it.operationName == "RegisterWebhook" } == 1)
    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }
    assert(ordersCreate.status is WebhookTopicStatus.Added)
    assert(ordersCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/1"))
  }

  // ---------- scanning ----------

  @Test
  fun `the scan asks Shopify for every handled topic without a url filter`() = runBlocking {
    stubExistingSubscriptions(emptyList())

    scanShopifyWebhooks(shopify, CALLBACK_URL)

    val variables = fake.calls.single { it.operationName == "GetWebhookSubscriptions" }.variables.jsonObject
    assert(variables["topics"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet() == ALL_TOPICS)
    // The client leaves out a variable whose value is null, so "no url filter" is a missing key, never a null.
    assert("uri" !in variables)
  }

  @Test
  fun `the scan sorts each topic into active, missing and stale without registering anything`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "PRODUCTS_CREATE", CALLBACK_URL),
        subscription(2, "PRODUCTS_CREATE", OLD_CALLBACK_URL),
        subscription(3, "ORDERS_UPDATED", OLD_CALLBACK_URL),
      ),
    )

    val report = (scanShopifyWebhooks(shopify, CALLBACK_URL) as Success).value

    assert(fake.calls.none { it.operationName == "RegisterWebhook" })
    val productsCreate = report.topics.single { it.topic == "PRODUCTS_CREATE" }
    assert((productsCreate.status as WebhookTopicStatus.Active).subscription.id == "gid://shopify/WebhookSubscription/1")
    assert(productsCreate.stale.map { it.id } == listOf("gid://shopify/WebhookSubscription/2"))
    val ordersUpdated = report.topics.single { it.topic == "ORDERS_UPDATED" }
    assert(ordersUpdated.status is WebhookTopicStatus.Missing)
    assert(ordersUpdated.stale.map { it.uri } == listOf(OLD_CALLBACK_URL))
    assert(report.missingCount == 4)
    assert(report.staleCount == 2)
  }

  /** Subscribed at our URL is not enough: the payload fields have to be the ones the topic declares, as a set. */
  @Test
  fun `the scan sorts a subscription at our url by its payload fields`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "ORDERS_CREATE", CALLBACK_URL, includeFields = emptyList()),
        subscription(2, "PRODUCTS_CREATE", CALLBACK_URL, includeFields = emptyList()),
        subscription(3, "ORDERS_UPDATED", CALLBACK_URL, includeFields = ORDERS_FIELDS.reversed()),
      ),
    )

    val report = (scanShopifyWebhooks(shopify, CALLBACK_URL) as Success).value

    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }.status as WebhookTopicStatus.Mismatched
    assert(ordersCreate.subscription.includeFields.isEmpty())
    assert(ordersCreate.expectedIncludeFields == ORDERS_FIELDS)
    assert(report.topics.single { it.topic == "PRODUCTS_CREATE" }.status is WebhookTopicStatus.Active)
    assert(report.topics.single { it.topic == "ORDERS_UPDATED" }.status is WebhookTopicStatus.Active)
    assert(report.topics.count { it.status is WebhookTopicStatus.Mismatched } == 1)
    assert(updateCalls().isEmpty())
  }

  /** A filter drops events, and XML is a body the webhook parsers cannot read: either makes a subscription at our url mismatched. */
  @Test
  fun `the scan sorts a subscription at our url with a filter or in xml into mismatched`() = runBlocking {
    stubExistingSubscriptions(
      listOf(
        subscription(1, "PRODUCTS_UPDATE", CALLBACK_URL, filter = "vendor:Acme"),
        subscription(2, "ORDERS_CREATE", CALLBACK_URL, format = WebhookSubscriptionFormat.XML),
        subscription(3, "PRODUCTS_CREATE", CALLBACK_URL, filter = ""),
      ),
    )

    val report = (scanShopifyWebhooks(shopify, CALLBACK_URL) as Success).value

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

    val report = (scanShopifyWebhooks(shopify, CALLBACK_URL) as Success).value

    val ordersCreate = report.topics.single { it.topic == "ORDERS_CREATE" }.status
    assert((ordersCreate as? WebhookTopicStatus.Active)?.subscription?.id == "gid://shopify/WebhookSubscription/2")
  }

  @Test
  fun `a failed scan is a failure the caller can answer from`() = runBlocking {
    fake.stubRaw("GetWebhookSubscriptions", """{"data":null,"errors":[{"message":"Throttled"}]}""")
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

  // ---------- helpers ----------

  private fun declaredIncludeFields(topic: String): List<String> =
    ShopifyWebhookTopic.known.single { it.subscriptionTopic!!.name == topic }.includeFields.orEmpty()

  private fun subscription(
    id: Int,
    topic: String,
    uri: String,
    includeFields: List<String> = declaredIncludeFields(topic),
    filter: String? = null,
    format: WebhookSubscriptionFormat = WebhookSubscriptionFormat.JSON,
  ) = ExistingSubscription(
    id = "gid://shopify/WebhookSubscription/$id",
    topic = WebhookSubscriptionTopic.valueOf(topic),
    uri = uri,
    includeFields = includeFields,
    filter = filter,
    format = format,
  )

  /** Every handled topic but [except] subscribed at our URL with its declared fields, so the test's own row is the only one in question. */
  private fun activeAtCallbackUrl(except: String? = null): List<ExistingSubscription> =
    ALL_TOPICS.filter { it != except }.mapIndexed { index, topic -> subscription(100 + index, topic, CALLBACK_URL) }

  private fun updateCalls(): List<FakeShopifyGraphqlServer.RecordedCall> =
    fake.calls.filter { it.operationName == "UpdateWebhookSubscription" }

  private fun stubExistingSubscriptions(subs: List<ExistingSubscription>) {
    fake.stubData(
      "GetWebhookSubscriptions",
      GetWebhookSubscriptions.Result(webhookSubscriptions = WebhookSubscriptionConnection(nodes = subs)),
      GetWebhookSubscriptions.Result.serializer(),
    )
  }

  private fun stubRegisterOk() {
    fake.stubData(
      "RegisterWebhook",
      RegisterWebhook.Result(
        webhookSubscriptionCreate = WebhookSubscriptionCreatePayload(
          userErrors = emptyList(),
          webhookSubscription = NewSubscription(
            id = "gid://shopify/WebhookSubscription/9999",
            topic = WebhookSubscriptionTopic.PRODUCTS_CREATE,
            includeFields = emptyList(),
          ),
        ),
      ),
      RegisterWebhook.Result.serializer(),
    )
  }

  private fun stubUpdated(
    id: Int,
    topic: String,
    uri: String,
    includeFields: List<String>,
    filter: String? = null,
    format: WebhookSubscriptionFormat = WebhookSubscriptionFormat.JSON,
  ) {
    fake.stubData(
      "UpdateWebhookSubscription",
      UpdateWebhookSubscription.Result(
        webhookSubscriptionUpdate = WebhookSubscriptionUpdatePayload(
          userErrors = emptyList(),
          webhookSubscription = UpdatedSubscription(
            id = "gid://shopify/WebhookSubscription/$id",
            topic = WebhookSubscriptionTopic.valueOf(topic),
            uri = uri,
            includeFields = includeFields,
            filter = filter,
            format = format,
          ),
        ),
      ),
      UpdateWebhookSubscription.Result.serializer(),
    )
  }

  private fun stubUpdateRefused(message: String) {
    fake.stubData(
      "UpdateWebhookSubscription",
      UpdateWebhookSubscription.Result(
        webhookSubscriptionUpdate = WebhookSubscriptionUpdatePayload(
          userErrors = listOf(UpdateUserError(field = listOf("id"), message = message)),
          webhookSubscription = null,
        ),
      ),
      UpdateWebhookSubscription.Result.serializer(),
    )
  }
}
