package dropnext.dss.testutil.fixture

import dropnext.dss.domain.WebhookSubscriptionStatus


/** A subscription as Shopify reports it: the full payload of every event, as JSON, unless a test says otherwise. */
internal fun webhookSubscriptionStatus(
  id: Int = 1,
  topic: String = "PRODUCTS_CREATE",
  uri: String = "https://dss.test/webhooks/shopify",
  includeFields: List<String> = emptyList(),
  filter: String? = null,
  format: String = "JSON",
): WebhookSubscriptionStatus =
  WebhookSubscriptionStatus(
    id = "gid://shopify/WebhookSubscription/$id",
    topic = topic,
    uri = uri,
    includeFields = includeFields,
    filter = filter,
    format = format,
  )
