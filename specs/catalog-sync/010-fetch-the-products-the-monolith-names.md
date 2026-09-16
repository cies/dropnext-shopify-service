# Spec: fetch the products the monolith names

Status: draft
Author: cies (with Claude)
Date: 2026-09-16
Depends on: nothing. Prerequisite for [020](020-list-the-shops-catalog-in-pages.md), for
`../../../dropnext-monolith/specs/catalog-sync/030-diff-the-catalog-and-queue-the-work.md`, and for
`../../../dropnext-monolith/specs/no-lost-shopify-orders/020-heal-the-pending-variants-before-matching-the-order.md`.
The end goal is described in `../../../dropnext-monolith/specs/catalog-sync/README.md`.
Repos: monolith first for the contract, then the DSS in the same work session.


## Problem

Every product that reaches the monolith today does so because Shopify sent a webhook. There is no way for the
monolith to say "give me this product", so there is no way to repair a catalog: not after an install, not after
a missed delivery, and not for an order line naming a variant nobody mirrored.

The DSS is the only side that can read Shopify, and it already knows how: `productById` loads a product,
`toProductVariantItems` maps it, and both run on every product webhook. What is missing is a door the monolith
can knock on.


## What changes

A new bearer-protected route, beside the two the monolith already calls:

`POST /products/fetch`, body `{ shopify_subdomain, product_ids: [ … ] }`, answering the product variant items for
those products, the ids Shopify no longer has, and what Shopify said about our remaining rate budget.

- **It reads and answers. It does not write.** The DSS makes no call back into the monolith here. The monolith
  orchestrates and owns the transaction that stores the result, which is what keeps this endpoint a query layer
  rather than a second, hidden ingest path.
- **The items are the contract's `ProductVariantItem`**, exactly what `POST /product-variants` accepts, so the
  monolith can hand the answer straight to its existing upsert.
- **`product_ids` is capped**, at `MAX_PRODUCTS_PER_FETCH`, ten or so. More than that answers `400`. The cap is
  about the answer, not the work: a product carries its whole description on every one of its variants, so a
  handful of large products is already a response worth megabytes.
- **A product Shopify no longer has** is not an error. Its id comes back under `missing_product_ids`, and the
  monolith soft-deletes its variants.
- **The throttle status travels with the answer.** Shopify reports `extensions.cost.throttleStatus` on every
  response, with the bucket's `maximumAvailable`, `currentlyAvailable` and `restoreRate`. The DSS reads it and
  puts the last one it saw in the response. This is what lets the monolith pace itself against the real budget
  of that shop's plan rather than against a number somebody guessed.
- **Per shop, not per call.** The route resolves the shop's Admin token the way every other route does, through
  the factory, so a shop without one answers `401` and one whose token cannot be looked up answers `502`.
- The products are loaded one at a time, in the order given, stopping at the first failure. Partial answers are
  worse than none here: the monolith would store some and count the batch done.

`workflow/` gains one function for this, composing `productById` and `toProductVariantItems` per id. It is the
same pair `syncShopifyProductToMonolith` composes, so the load-and-map step is extracted and shared rather than
written twice.


## Behavioral contract

- **Precondition**: a valid bearer token, a shop that parses, between one and `MAX_PRODUCTS_PER_FETCH` product
  ids, each a Shopify product id.
- **Postcondition**: the answer carries every variant of every requested product that Shopify still has, mapped
  exactly as a `products/update` webhook would map it, plus the requested ids Shopify does not have.
- **Invariant**: the endpoint changes nothing, in Shopify or in the monolith. It can be called twice with the
  same body and the second call is as cheap and as harmless as the first.
- **Invariant**: a variant list is complete or the call fails. It never answers a truncated product, which is
  what lets the monolith upsert with the prune flag of
  `../../../dropnext-monolith/specs/prune-variants-shopify-no-longer-has/010-soft-delete-the-variants-a-complete-payload-omits.md`.
  This is the dependency on `../paged-graphql-connections/010-fail-loudly-on-a-truncated-connection.md` and
  `020`: without them a product with many variants answers its first hundred and calls that complete.
- **Invariant**: one Shopify failure fails the whole call. The monolith retries the batch.


## Edge cases

- **A product id that was never a product**, or belongs to another shop: Shopify answers no product, so it is
  reported as missing. The monolith then soft-deletes variants it has under that id, of which there are none.
- **A shop with no Admin token**: `401`, and the monolith's job stops rather than retrying, because no retry
  fixes it.
- **Throttled mid-batch**: the whole call fails with the retryable Shopify error, the monolith's job backs off
  and retries the same batch. The `throttleStatus` of the failing response still travels, so the monolith learns
  it is out of budget from the failure as well as from a success. See open question 3.
- **A duplicated id in the request**: loaded once. The answer carries its variants once.
- **An empty `product_ids`**: `400`. A caller with nothing to fetch should not call.
- **A product with no variants**: impossible in Shopify, and answered as an empty item list rather than as
  missing if it ever happens.


## Reuse inventory

- `productById` and `ShopProduct` in `lib/shopify/graphql/`, unchanged.
- `toProductVariantItems` in `mapper/`, unchanged.
- `syncShopifyProductToMonolith` in `workflow/`, whose load-and-map half is extracted here and used by both.
- `monolithWebhookRoutes` and `MONOLITH_WEBHOOK_AUTH`: the route family and the bearer guard already exist.
- `MonolithWebhookHandlers`, which already resolves a shop and maps a workflow's answer onto a response.
- `shopDomainOrRespond` and `shopifyServiceOrRespond` in `handler/`.
- `Paths` in `path/`, which holds every inbound path constant.
- `installRequestValidation`, where the body's validator is registered like the other three.
- `ProductFixtures` and `FakeShopifyGraphqlService` for the tests.


## Test plan

- **Pure**: the request validator. An empty id list, more ids than the cap, a blank subdomain.
- **Fake-backed**: two products answer both their variant sets; a product Shopify does not have is reported
  missing and the others still answer; a Shopify failure on the second product fails the call and the first
  product's items are not answered; the throttle status is carried through.
- **Request → response** (`MonolithWebhookHandlersTest`): a valid call answers `200` with the items; no bearer
  answers the bare `401`; a shop without a token answers `401` with the `ApiError`; a shop whose token cannot be
  looked up answers `502`.
- **Wire** (`HttpShopifyGraphqlServiceTest`): `extensions.cost.throttleStatus` is read off a real-shaped
  response, including one that carries no `extensions` at all.


## Open questions for the human developer

1. **Should the DSS push instead of answering?** It could load each product and post it to the monolith's own
   `POST /product-variants`, which reuses the webhook path completely and keeps every payload small. The cost is
   that orchestration then straddles both services and a partial batch becomes possible. Answering is proposed,
   because the monolith is the orchestrator and because one transaction per batch is easier to reason about.
2. **Is ten the right cap?** It bounds a response that can hold ten full product descriptions repeated once per
   variant. Five is the safer starting point and costs only more round trips, which this flow has time for.
3. **Should the throttle status be answered on a failure too?** It is most useful exactly when the call failed
   for being throttled. That means a failure body richer than `ApiError`, which every other route answers, so it
   trades a uniform error shape for a better signal.
4. **Should this endpoint take variant ids rather than product ids?** Both callers happen to know the product
   id, so product ids need no resolution step. Variant ids would need a lookup of the variant's product before
   anything else, which is one more Shopify call per batch.
