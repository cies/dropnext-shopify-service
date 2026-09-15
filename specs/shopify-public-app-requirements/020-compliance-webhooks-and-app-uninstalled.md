# Spec: compliance webhooks and `app/uninstalled`

Status: draft, not built: the app is custom-distributed (`000`), and Shopify requires the compliance topics only of
App Store apps. Checked against Shopify's documentation on 2026-09-16.
Author: cies (with Claude)
Date: 2026-09-12
Depends on: `000` for the compliance topics. `app/uninstalled` is worth having whatever the distribution: Shopify's API
Terms require deleting a merchant's data within 30 days of an uninstall from custom apps too, and today the monolith
does not learn of one.
Repos: monolith first (it holds the data and serves the contract), then the DSS; the app configuration in Shopify.


## Problem

- **Compliance topics.** An app distributed through the Shopify App Store must handle `customers/data_request`,
  `customers/redact` and `shop/redact`, and these can only be subscribed in the app's configuration, not through the
  `webhookSubscriptionCreate` mutation that `registerShopifyWebhooks` uses. Nothing subscribes them today. A delivery
  that did reach `/webhooks/shopify` would be verified and then answered `200 skipped topic_not_mirrored`, having done
  nothing.
- **Where the data is.** The DSS keeps no customer data: it is stateless and caches tokens only. The monolith does: every
  order mirrored through `POST /orders` carries the customer's shipping address, name and phone number (folded into
  `delivery_addresses`) and the buyer's email (`shopify_orders.email`), and supplier orders repeat the address.
- **Uninstalls.** Shopify revokes the app's token when a merchant uninstalls it. Nothing tells the monolith: it keeps the
  dead token in `stores.encoded_api_key`, keeps queuing shipment syncs and tracking updates for the shop, and each of
  them ends in a `401` (`DssError.ShopifyAdminTokenRejected`) that reads the same as a broken install.
- `specs/webhook-deduplication/000-analysis-and-design.md` names `app/uninstalled` and the compliance topics as needing
  a design of their own; this is that design.


## What Shopify does

From Shopify's privacy law compliance documentation (see "Sources"; confirm the details when this spec is picked up):

- **Who.** "Any app that you distribute through the Shopify App Store must respond to data subject requests, regardless
  of whether the app collects personal data." Unlisted ("limited visibility") apps are distributed through the App Store
  too. Custom-distribution apps are outside the scope; no page grants them an explicit exemption. The current App Store
  requirements page no longer lists the topics as a review item, while the privacy page still says an app without them
  is rejected.
- **Configuration.** The compliance topics are declared in the app configuration (`shopify.app.toml`,
  `[[webhooks.subscriptions]]` with `compliance_topics`), deployed with the Shopify CLI. Shopify also recommends that
  configuration for ordinary topics; subscriptions declared there do not show up in the Admin API's
  `webhookSubscriptions` query ("Returns only shop-scoped subscriptions, not app-scoped subscriptions configured in TOML
  files"). The Dev Dashboard can declare them as well. Two consequences here: `/api/check` cannot see a declared
  subscription, and the webhook registration never deletes one, since it only acts on what that query returns.
- **Answers.** A `200`-series status for a valid request; `401 Unauthorized` when the HMAC does not verify, which
  `ShopifyWebhookHandlers` already answers.
- **Deadlines.** The action is completed within 30 days of the request, unless the law requires keeping the data.
  `shop/redact` arrives 48 hours after the store owner uninstalls the app. `customers/redact` arrives 10 days after the
  deletion request when the customer has placed no order in the past six months; otherwise Shopify withholds it until
  six months have passed.
- **Payloads.** `customers/data_request`: `shop_id`, `shop_domain`, `orders_requested[]`, `customer {id, email, phone}`,
  `data_request {id}`. `customers/redact`: `shop_id`, `shop_domain`, `customer {id, email, phone}`, `orders_to_redact[]`.
  `shop/redact`: `shop_id`, `shop_domain`.
- **Beyond the webhooks.** The API Terms oblige every app, custom ones included, to delete a merchant's data within 30
  days of an uninstall or of "an enforceable request to delete data from a Merchant, a Customer or Shopify".


## What changes

### DSS

- `ShopifyWebhookTopic` gains `AppUninstalled` (registered through the API like today's topics, open question 4),
  `CustomersDataRequest`, `CustomersRedact` and `ShopRedact` (no `subscriptionTopic`: not registered by the DSS).
- `app/uninstalled`:
  - the token store drops whatever it caches for the shop (a `forgetAll(shop)` beside the token-bound `forget`);
  - the monolith is told (a new contract endpoint, open question 2), which clears the token and marks the store
    uninstalled;
  - the outcome is `Mirrored` once the monolith answered, `MonolithFailed` otherwise, so a monolith outage is redelivered.
- Compliance topics: forwarded to the monolith with the shop, the topic, the webhook id and the ids from the payload.
  The answer is a `200` once the monolith has recorded the request, a `502` when it could not (Shopify redelivers). The
  DSS never needs the Admin token for these, so they run for a shop without one.
- `WebhookSkipReason` and the delivery report labels gain nothing: each of these is mirrored or failed.
- `/api/check` and the install page report whether the shop is subscribed to `app/uninstalled`, which they can only if
  it is registered through the API (open question 4).

### Monolith

- Contract endpoints for an uninstall and for a privacy request.
- A `privacy_requests` table: topic, store, customer id, order ids, Shopify's webhook id (unique, so a redelivery
  records nothing twice), received and completed timestamps.
- Jobs: `customers/redact` removes or anonymizes the customer's personal data on the named orders; `shop/redact` removes
  the store's Shopify data; `customers/data_request` produces what the store owner has to hand over (open question 3).
  The monolith keeps no Shopify customer id, so both customer topics are keyed by the order ids in the payload, within
  the store. Matching on the stored email instead would reach orders Shopify did not name.
- The uninstall clears `encoded_api_key` and stops the shop's sync and tracking jobs from calling the DSS.

### App configuration

- A `shopify.app.toml` (or the Partner Dashboard equivalent while it exists) declaring the compliance topics at
  `{DSS_BASE_URL}/webhooks/shopify`, one per environment, since staging and production have different base URLs.


## Behavioral contract

- **Every compliance delivery is either recorded by the monolith or redelivered by Shopify**; none is acknowledged
  without being recorded.
- **Idempotence**: a redelivered compliance request or uninstall changes nothing the first delivery did not.
- **Order of effects on uninstall**: the DSS's cache is dropped even when the monolith call fails, so no request of this
  task uses a revoked token again.


## Edge cases

- **An uninstall for a shop the DSS does not cache**: forgetting is a no-op; the monolith is told all the same.
- **A reinstall within 48 hours**: whether Shopify still sends `shop/redact` is to be confirmed on a development store
  before the redaction job deletes anything.
- **Orders already passed to suppliers**: the supplier orders carry the shipping address too; what redaction means there,
  and which records bookkeeping law requires to keep, is a business question (open question 5).
- **A compliance request for a shop the monolith does not know**: recorded and answered `200`, since there is nothing
  to redact.
- **A delivery with a bad HMAC**: `401`, as for every topic.


## Reuse inventory

- `ShopifyWebhookHandlers` (HMAC verification, the per-topic dispatch, the outcome-based answer and the delivery report),
  `ShopifyWebhookTopic` and `registerShopifyWebhooks`.
- `ShopTokenStore.forget` (token-bound today) for the eviction.
- `MonolithService` plus its wire test (`TestSuiteArchitectureTest` requires one per new method) and `FakeMonolithService`.
- Monolith: the job pattern of `SyncShipmentsWithFulfillments`; the store token columns in `shopifyServiceApiWrite.kt`.


## Test plan

- **DSS, request → response** (`ShopifyWebhookHandlersTest`): each compliance topic with a valid HMAC reaches the
  monolith and is answered `200`; with a bad HMAC it is a `401` and reaches nothing; a monolith outage is a `502`;
  `app/uninstalled` evicts the cached token and tells the monolith, also for a shop without a token.
- **DSS, wire** (`HttpMonolithServiceTest`): the new calls.
- **Monolith** (`*DbTest`, job tests): a redelivered request is recorded once; each redaction touches exactly the named
  customer's data; an uninstall clears the token and stops the shop's DSS jobs.
- **By hand, on a development store**: trigger each compliance topic from the Partner Dashboard and uninstall the app;
  follow each delivery by its trace id.


## Open questions for the human developer

1. Is the app public? The compliance topics are required only then. **Answered 2026-09-16:** no, it is
   custom-distributed (`000`); this spec waits until that changes.
2. One monolith endpoint per topic, or one privacy-request endpoint that takes the topic?
   - Per topic: three routes, DTOs, `MonolithService` methods, wire tests and fake entries; the generated types stop
     the DSS from building a request that lacks what its topic needs.
   - One endpoint, `topic` as an enum and the fields the topics share (store, webhook id, order ids, a nullable data
     request id): one of each; the monolith checks per topic that the request is complete.
   - The verified Shopify payload passed through as it came: the monolith would parse Shopify's format, which the DSS
     exists to keep out of it, and would store the customer's email and phone.
   - Either way, a request the monolith refuses as incomplete is a bug and must not be acknowledged to Shopify.
3. What does `customers/data_request` produce, and how does it reach the store owner?
4. `app/uninstalled` registered through the API, like today's topics, or declared in the app configuration together
   with the compliance topics?
5. Which monolith records hold the customer's personal data (orders with their delivery addresses and email, supplier
   orders, invoices), and which of them must be kept for bookkeeping rather than redacted?


## Sources

- [Shopify: privacy law compliance](https://shopify.dev/docs/apps/build/compliance/privacy-law-compliance)
- [Shopify: subscribe to webhook topics](https://shopify.dev/docs/apps/build/webhooks/subscribe)
- [Shopify Admin Graphql: `webhookSubscriptions`](https://shopify.dev/docs/api/admin-graphql/latest/queries/webhookSubscriptions)
- [Shopify: App Store requirements](https://shopify.dev/docs/apps/launch/shopify-app-store/app-store-requirements)
- [Shopify API Terms](https://www.shopify.com/legal/api-terms)
