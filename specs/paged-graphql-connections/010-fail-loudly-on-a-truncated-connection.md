# Spec: fail loudly when a Graphql connection was truncated

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: nothing. Prerequisite for `020`, and for any future "prune the variants absent from the payload"
logic in the monolith: without it, a truncated page turns into a deletion. It also sets the rule for connections
added later: `specs/shipment-sync-by-tracking-number/020-cancel-replaced-fulfillments.md` adds
`fulfillments.fulfillmentLineItems` and follows it.
Repos: DSS only.


## Problem

Every Graphql connection the service reads is fetched with a fixed `first` and no `pageInfo`, so nothing can
tell a complete list from the first page of a longer one:

| Operation                 | Connection                    | `first`   | What silently happens past the limit                                                                                                                                                          |
|---------------------------|-------------------------------|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GetProductById`          | `product.variants`            | 100       | The `products/create` and `products/update` webhooks upsert the first 100 variants; the rest never reach the monolith. Shopify allows 2048 variants per product.                              |
| `GetProductById`          | `product.media`               | 20        | Images beyond the twentieth are not sent. Cosmetic.                                                                                                                                           |
| `GetOrderForDss`          | `order.lineItems`             | 100       | The `orders/create` webhook creates the order with its first 100 lines.                                                                                                                       |
| `GetOrderForDss`          | `order.fulfillmentOrders`     | 50        | The matcher reports "no open fulfillment order" for lines that live on a fulfillment order it never saw, and `/sync-shipments-with-fulfillments` skips them as unmatched.                     |
| `GetOrderForDss`          | `fulfillmentOrders.lineItems` | 100       | Same, per fulfillment order.                                                                                                                                                                  |
| `GetOrderForDss`          | `order.fulfillments`          | 50        | Not a connection (`fulfillments(first:)` answers a plain list), so it has no `pageInfo`; a tracking number on the fifty-first fulfillment is not found, and `/tracking-update` answers `404`. |
| `GetWebhookSubscriptions` | `webhookSubscriptions`        | 100       | Filtered to our topics and our callback URI, so a handful at most. No change.                                                                                                                 |

`GetOrderForDss` has three readers, and all three inherit its limits: the `orders/create` webhook
(`syncShopifyOrderToMonolith`), `/sync-shipments-with-fulfillments` (`determineShopifyMutations`) and
`/tracking-update` (`syncShopifyTrackingEvent`).

`variants` is the one limit a real catalogue reaches (`020` pages it). The others are rare, but when they
happen the service answers `200` with a wrong mirror, which is the failure mode a sync must never have.


## What changes

- Every connection in the table above except the "no change" row asks for `pageInfo { hasNextPage }`.
  `variants.media(first: 1)` is deliberately the first image and is left alone.
- `HttpShopifyGraphqlService` checks `hasNextPage` on every connection it answers, in the method that owns
  the payload (`productById`, `orderForDss`), before the snapshot leaves `lib/shopify/`. This is the triage
  point the Graphql rules name; a workflow never sees `pageInfo`.
- A new sealed member `ShopifyError.Truncated(val connection: String, val pageSize: Int)`, with a message like
  `Shopify has more product.variants than the 100 this service loads`. It is the answer of `productById` when
  `variants` has a next page, and of `orderForDss` when `lineItems`, `fulfillmentOrders` or any fulfillment
  order's `lineItems` has one.
- `product.media` is the exception: a next page is logged at `warn` (`Product media truncated productGid=…
  first=20`) and the product is mirrored with the images it has. An image count must not stop a variant sync.
- `order.fulfillments(first: 50)` cannot report truncation. Raise it to 250. The schema documents no maximum for
  this argument (its only description is "Truncate the array result to this size"); 250 is the usual ceiling of
  Shopify's `first` arguments, so confirm in the Graphql explorer that this list accepts it before relying on it.
  Document the remaining limit in `docs/FULFILLMENT_VERIFICATION.md` under "Known limitations";
  `specs/shipment-sync-by-tracking-number/040-rewrite-the-fulfillment-docs.md` rewrites that document later and
  keeps the sentence.
- Mapping the failure:
  - **Shopify webhooks.** `Truncated.isRetryable` is `false`, so `WebhookMirrorOutcome.isTransient` is too: a
    redelivery loads the same product. The handler answers `200`, and the `WebhookDeliveryReport` line is at `error`
    level with
    `error=shopify_truncated`, in the family of the existing `shopify_…` labels, which is what an operator
    searches for.
  - **Monolith-facing routes.** `ShopifyError.toDssError()` maps `Truncated` to a new `DssError.Unsupported`
    answered as `500`, with the same generic message the `Internal` error uses ("internal error"); the connection
    and the page size go to the log at `error`. Not a `502`: nothing upstream failed, and the status is the first
    thing an operator reads. Not a `400`: nothing in the request is wrong. For the monolith the two `5xx` codes
    behave the same: its `integrationErrorFor` (`lib/integration/IntegrationError.kt`) treats every status that is
    not a `4xx` (other than `408` and `429`) as a transport failure, so the `SyncShipmentsWithFulfillments` job
    retries it until pgmq moves the message to its dead-letter queue, and the shipments stay unstamped either way.
    Open question 1 asks whether a `4xx` is the better answer.
  - **`/tracking-update`** answers the same `500` for an order whose line items or fulfillment orders were
    truncated, although it reads only `fulfillments`. One snapshot, one rule; open question 3 asks whether the
    route should get a query of its own.


## Behavioral contract

- **Precondition**: a Shopify response that decoded. Truncation is checked after the existing triage (transport,
  decoding, top-level `errors`, `NotFound`), so a `Truncated` answer means the resource exists.
- **Postcondition**: a `Success` from `productById` carries every variant of the product, and a `Success` from
  `orderForDss` carries every line item and every fulfillment order with every line, or the call answers
  `Truncated` and nothing downstream runs on a partial snapshot.
- **Invariant**: no `200` to Shopify and no `200` to the monolith is ever computed from a truncated list.
- **Unchanged**: the page sizes stay as they are, apart from `fulfillments`; `020` changes how variants are loaded.


## Edge cases

- `hasNextPage` is `true` on a nested connection only (one fulfillment order with more than 100 lines): the
  whole `orderForDss` answers `Truncated("fulfillmentOrders.lineItems", 100)`; the name says which level.
- A product with exactly 100 variants: `hasNextPage` is `false`, a normal sync.
- The `products/delete` webhook never loads the product, so it is unaffected; a shop whose product could not
  be synced because of truncation still gets its deletes.
- `media` truncated and `variants` truncated on the same product: `Truncated` wins, and the media warning is not
  logged (the sync did not happen).
- The install page's product count (`ProductsCount`) is not a connection and never fails on paging.
- `/tracking-update` on an order with more than 50 fulfillment orders: `500`, although the fulfillment it looks
  for was loaded.
- A tracking number on a fulfillment beyond the 250th: not found, `404`, as today; the documented limit.


## Reuse inventory

- `ShopifyError.isRetryable`, which `WebhookMirrorOutcome.isTransient` reads: the new member answers `false`, like
  `TokenRejected`, `UserError`, `NotFound` and `Undecodable`.
- `handler/toDssError.kt`: the one `ShopifyError` → `DssError` mapping.
- `lib/ktor/DssError.kt`: `Internal` is the model for a `500` whose message stays generic; `toHttpStatus` gains the
  new member.
- `HttpShopifyGraphqlService.execute` and the payload-owning methods: the triage point.
- `WebhookDeliveryReport.errorLabel`: its `when` is exhaustive over `ShopifyError`, so the new member needs its
  label (`shopify_truncated`; `WebhookDeliveryReportTest` pins the existing ones).
- Fixtures: `minimalOrder()`, `orderWithFulfillment()`, `diagramCrossFoOrder()`, `openFulfillmentOrder()`
  (`testutil/fixture/`) and `sampleProduct()` build the generated types and gain the `pageInfo` field with a
  `hasNextPage = false` default; the canned JSON in `FakeShopifyGraphqlServer` gains the same key.


## Test plan

- **Wire** (`HttpShopifyGraphqlServiceTest`): `productById` with `variants.pageInfo.hasNextPage = true` answers
  `Failure(Truncated("product.variants", 100))`; with `media` truncated only, `Success` plus the warn line;
  `orderForDss` with a nested `fulfillmentOrders.lineItems` next page answers the nested name.
- **Fake-backed** (`SyncShopifyProductToMonolithTest`): a `Truncated` failure is `ShopifyFailed`, not transient,
  and the monolith is not called.
- **Request → response**:
  - `ShopifyWebhookHandlersTest`: `products/update` on a truncated product is `200` with
    `error=shopify_truncated` in the body and an `error`-level line.
  - `MonolithWebhookHandlersTest`: `sync-shipments` on an order with truncated fulfillment orders is `500` with
    the generic message and no `createFulfillment` call; `tracking-update` on the same order is `500` and no
    `createFulfillmentEvent` call.
- **Pure** (`ShopifyGraphqlServiceTest`, `ToDssErrorTest`): `Truncated` is not retryable; `toDssError` maps it to the `500`.


## Open questions for the human developer

1. A `500` for the monolith-facing routes, as written, or a `4xx` such as `422`? A `5xx` is retried by the
   monolith's job until pgmq dead-letters the message, where it waits for a human. A `4xx` is logged by the
   monolith at `error` and not retried, and the shipments stay unstamped until the job runs again. A `502` is not a
   third option: the monolith treats it exactly like a `500`.
2. Should `media` truncation stay a warning, or is a product with more than twenty images rare enough to
   fail too and keep the rule uniform?
3. Should `/tracking-update` get its own query that loads only `fulfillments`, so that a truncated fulfillment-order
   list cannot fail an event for a fulfillment that was loaded?


## Sources

- [Shopify changelog: the product variant limit is now 2048 for all merchants](https://shopify.dev/changelog/the-product-variant-limit-is-now-2048-for-all-merchants).
- [Shopify Graphql Admin API: the `Order` object](https://shopify.dev/docs/api/admin-graphql/latest/objects/Order):
  the arguments of `fulfillments`, with no documented maximum for `first`.
