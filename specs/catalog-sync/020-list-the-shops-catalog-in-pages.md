# Spec: list the shop's catalog in pages

Status: draft
Author: cies (with Claude)
Date: 2026-09-16
Depends on: [010](010-fetch-the-products-the-monolith-names.md) for the route family and the shared shape.
Prerequisite for `../../../dropnext-monolith/specs/catalog-sync/030-diff-the-catalog-and-queue-the-work.md`.
The end goal is described in `../../../dropnext-monolith/specs/catalog-sync/README.md`.
Repos: monolith first for the contract, then the DSS in the same work session.


## Problem

To repair a catalog the monolith has to know what is in it. It knows what it holds; it has no way to learn what
Shopify holds. `010` fetches products by id, which presupposes the ids.


## What to list: three ways

The choice decides what the diff can detect, so it is worth making explicitly.

**1. Variant ids, each with its product id. Recommended.** One flat connection,
`productVariants(first:, after:) { nodes { legacyResourceId product { id } } }`. `ProductVariant.product` is a
plain object, not a connection, so no nesting is involved. This is the only option that detects every kind of
difference: a product added, a product deleted, and a variant added to or removed from a product that still
exists. The last one is invisible to a product-level diff and is exactly the drift that a missed
`products/update` leaves behind. The product id travels with each variant, so the work still groups into
products and `010` still takes product ids.

**2. Product ids only.** `products(first:, after:) { nodes { legacyResourceId } }`. Fewer items to page through,
since a shop has far fewer products than variants, and a cheaper query. It cannot see inside a product, so a
variant removed from a surviving product is never noticed. That gap is closed on the webhook path by the prune
flag of
`../../../dropnext-monolith/specs/prune-variants-shopify-no-longer-has/010-soft-delete-the-variants-a-complete-payload-omits.md`,
but a backfill exists precisely for the shops where webhooks were missed, so relying on the webhook path to fix
what the backfill cannot see is circular.

**3. A bulk operation.** `bulkOperationRunQuery` is Shopify's purpose-built answer for exporting a whole
catalog: one mutation, then poll until it finishes, then download a JSONL file. It costs almost nothing against
the rate budget, which is the one thing this end goal has to be careful with, and it scales to any catalog
size. It is also a different shape of integration: an asynchronous operation to poll, a file to stream and parse,
a URL that expires, and one bulk operation per shop at a time. It is the right answer for a shop with hundreds of
thousands of variants and too much machinery for the shops we have. Worth revisiting when a listing run starts
taking minutes; `productVariantsCount` makes that measurable before it becomes a problem.

This spec takes option 1 and notes where option 3 would slot in.


## What changes

A second bearer-protected route: `GET /products/ids?shop=…&cursor=…`, answering
`{ items: [ { product_variant_id, product_id } … ], next_cursor }`.

- **Shopify's cursor is passed through, not interpreted.** The DSS hands back what Shopify called `endCursor`
  when `hasNextPage` is true, and `null` when it is not. The monolith loops until `next_cursor` is null and does
  not need to understand it.
- **One page per call.** The DSS does not page internally. A shop with fifty thousand variants would otherwise
  be one response of fifty thousand rows, and the monolith could neither pace nor resume it.
- **The page size is the DSS's**, `CATALOG_PAGE_SIZE`, not the caller's, so the cost of a page is a property of
  this service and can be tuned against measurement in one place. Two hundred and fifty is the starting point,
  subject to `../paged-graphql-connections/000-analysis-query-cost-and-page-sizes.md`.
- **The throttle status travels**, exactly as in `010`, so the monolith can pace the listing loop as well as the
  fetching.
- A new `.graphql` operation and one new single-shot primitive on `ShopifyGraphqlService`, answering our own
  page type rather than the generated one.


## Behavioral contract

- **Precondition**: a valid bearer token, a shop that parses and has a resolvable Admin token, and either no
  cursor or one this shop's previous page answered.
- **Postcondition**: the answer holds one page of the shop's variants, each with the product it belongs to, and
  a cursor for the next page or `null` at the end.
- **Invariant**: reading the whole catalog is the caller's loop, and every page is a separate, retryable call.
  A page that fails costs that page.
- **Invariant**: read-only, and repeatable. The same cursor answers the same page.
- **Not a snapshot.** A catalog edited while the loop runs gives a listing that is neither the before nor the
  after. The diff that consumes it must be safe under that, which is what
  `.../030-diff-the-catalog-and-queue-the-work.md` has to establish.


## Edge cases

- **An empty catalog**: one page, no items, `next_cursor` null.
- **A cursor Shopify refuses**, because it is stale or from another shop: Shopify answers a Graphql error, which
  is the usual upstream failure and a `502`. The monolith's run starts again from the beginning rather than
  resuming, which is cheap because listing is the cheap half.
- **A variant whose `legacyResourceId` will not parse**: left out of the page and logged once. It could never
  match a monolith row anyway.
- **The catalog changes mid-loop**: a product created after the loop passed its cursor position is not in the
  listing, so the diff does not see it. The next webhook or the next run catches it. A product deleted mid-loop
  may appear in the listing and then be missing when `010` fetches it, which `010` already answers as missing.
- **A shop that grows past what the loop can finish** inside whatever bound the monolith's job has: the run
  resumes from its stored cursor, which is why the cursor is answered rather than hidden.


## Reuse inventory

- `monolithWebhookRoutes`, `MONOLITH_WEBHOOK_AUTH` and `WebhookSubscriptionHandlers`, the closest existing
  shape: a bearer-protected `GET` taking `?shop=` and answering a report.
- `shopDomainOrRespond` and `shopifyServiceOrRespond`.
- `ShopifyGraphqlService` and its single-shot primitive rule, and `HttpShopifyGraphqlService.execute` for the
  triage and the throttle status `010` adds.
- `GetWebhookSubscriptions.graphql` as the model for a listing operation.
- `Paths`, `AppJson`, and the `@Serializable` response DTOs the subscription handlers already declare privately.


## Test plan

- **Wire** (`HttpShopifyGraphqlServiceTest`): a page deserializes with its cursor; a last page answers a null
  cursor; a variant with an unparseable id is skipped.
- **Fake-backed**: the primitive is called with the cursor it was given.
- **Request → response** (`WebhookSubscriptionHandlersTest` or its sibling): a first page and a follow-up page;
  no bearer is the bare `401`; an unknown shop is `401`; an unresolvable token is `502`.


## Open questions for the human developer

1. **Should the listing include the variant's `updatedAt`**, so a future run can skip products that cannot have
   changed? It would make repeated runs much cheaper and costs one scalar per row. It also invites a
   "last successful run" timestamp, which is a bigger idea than this end goal needs.
2. **Should option 2, product ids only, be offered as a mode** for a shop where the variant listing is too slow?
   It halves the pages for a shop with many variants per product, at the cost of the detection the recommendation
   is chosen for.
3. **Two hundred and fifty per page, or fewer?** The cost of a page grows with the page size and the nested
   `product { id }` is charged per row. This is the same measurement the cost analysis asks for.
