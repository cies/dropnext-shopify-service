# Query cost: what is knowable, and the ways to approach it

Status: analysis. No numbered spec of its own; it settles the page-size questions that `010` and `020` both
defer, and it may add a step to `010`.
Author: cies (with Claude)
Date: 2026-09-16
Repos: DSS only.


## Why this exists

`010` raises `order.fulfillments` to 250 and says to confirm the argument is accepted before relying on it.
`020` asks whether a variant page should be 100 or 250 and says to measure before raising it. Both are blocked on
the same number, which nobody in this repo has: what our queries actually cost.

A separate worry prompted this document. `GetOrderForDss` nests `fulfillmentOrders(first: 50)` over
`lineItems(first: 100)` over an object, and if a connection's cost is its page size multiplied by the cost of
what it returns, that nesting alone requests thousands of points against a documented cap of 1,000.


## What Shopify documents, and what it does not

Documented, on [API limits](https://shopify.dev/docs/api/usage/limits):

| Return type | Cost |
|---|---|
| Scalar, enum | 0 |
| Object | 1 |
| Interface, union | the maximum of the possible selections |
| Connection | "sized by the `first` and `last` arguments" |
| Mutation | 10 |

A single query may not exceed **1,000 points**, whatever the plan. The bucket refills at 100 points a second on
Standard, 200 on Advanced, 1,000 on Plus and 2,000 on Enterprise. Every response carries `extensions.cost` with
`requestedQueryCost`, `actualQueryCost` and a `throttleStatus`.

**Not documented: the formula.** The page says a connection is sized by its page argument and stops there. It
gives no worked example and no rule for nesting. So the cost of our queries cannot be derived from the
documentation by anyone, and an estimate built on "it probably multiplies" is a guess.

## The observation that settles the alarm

`requestedQueryCost` is computed **before execution, from the selected fields**. It does not depend on how much
data a shop has. So a query whose requested cost exceeded 1,000 would be refused every single time, for every
shop, with `MAX_COST_EXCEEDED`.

`GetOrderForDss` is not refused. Orders have been mirroring through it since 2026-08-31, per
`docs/FULFILLMENT_VERIFICATION.md`, and `MAX_COST_EXCEEDED` is a code the error triage would surface as
`error=shopify_graphql codes=MAX_COST_EXCEEDED` on the delivery line.

Therefore the requested cost of `GetOrderForDss` is at or below 1,000 as it stands, and the multiplying estimate
is wrong. The alarm is unfounded. What remains is that we are flying blind on a budget with a hard ceiling, and
two specs want to spend more of it.


## The real risk is the rate, not the ceiling

The 1,000-point cap applies to one query. The bucket is what a burst spends. A bulk product edit produces
thousands of webhook deliveries in a minute, and each one costs a `GetProductById` against a bucket that refills
at 100 points a second on a Standard plan. `MAX_CONCURRENT_MIRRORS` bounds how many we run at once, and Shopify's
growing redelivery interval spreads the rest, but nothing here knows what a delivery costs, so nothing can say
how many deliveries a second a shop can sustain.

Paging makes this sharper, not softer. `020` turns one product webhook into up to 26 calls.


## The ways to approach it

**1. Measure, then decide.** Read `extensions.cost` from every Shopify response and log it: the operation name,
`requestedQueryCost`, `actualQueryCost`, and `throttleStatus.currentlyAvailable`. One place, `HttpShopifyGraphqlService.execute`,
which already inspects the response envelope and already has the operation name available the way
`ShopifyDeprecationWarnings` finds it. Cheap, reversible, changes no behavior, and turns both open questions
into arithmetic. It also gives the burst answer, because `currentlyAvailable` over a bulk edit is exactly the
graph an operator needs. This is the recommended first step, and the only one that should happen before the
others are judged.

Sampling is worth considering: one line per operation per minute rather than one per call, so a bulk edit does
not double its own log volume. The deprecation plugin's "remember what has been reported" shape is the model.

**2. Split the order query by reader.** `GetOrderForDss` serves three callers that each need a third of it. The
order sync needs the header and `lineItems`; the shipment sync needs `fulfillmentOrders` and `fulfillments`; the
tracking update needs `fulfillments` only. Three narrower operations would each cost a fraction of the current
one, and `010`'s open question 3 already asks for the tracking one. The price is three operations to keep in step
and three sets of fixtures, against one shape that is easy to reason about. Worth doing if measurement shows the
combined query is near the ceiling, and not worth doing on suspicion.

**3. Page the nested connections.** `fulfillmentOrders` and its `lineItems` would follow the pattern `020`
establishes for variants. This is the only approach that removes a ceiling rather than moving it, and it is the
most work: a nested cursor loop, a page cap, and a snapshot assembled from several responses. It is what to do
when a real order actually exceeds 50 fulfillment orders, which no shop of ours has done.

**4. Lower the page sizes.** The opposite of what `020` asks. Cutting `lineItems` to 50 halves a chunk of the
cost and doubles the number of orders that truncate, which after `010` means loudly failing more webhooks. Only
sensible as a stopgap if measurement shows we are close to the cap.

**5. Leave it and rely on the error handling.** `MAX_COST_EXCEEDED` is already classified as not retryable and
`THROTTLED` as retryable, with a test pinning both. A query that grew too expensive would fail loudly rather than
silently. This is the honest null option: the failure mode is safe, it is just late, and it arrives as lost
webhook deliveries rather than as a number on a dashboard.


## Recommendation

Do 1. It is a handful of lines in the one place that already triages the response, it answers `010`'s question
about `fulfillments(first: 250)` and `020`'s question about the variant page size with data, and it costs
nothing if the answer is "we are nowhere near the cap". Fold it into `010` as one more thing its triage point
does, or land it on its own first, since it is independent of everything else in this folder.

Judge 2 and 3 on the numbers. Do not do 4 or pre-emptively redesign anything on an estimate the documentation
does not support.


## What to record once measured

For each operation, against a shop with a realistic catalog: `requestedQueryCost`, `actualQueryCost`, and the
headroom to 1,000. Then, for a bulk edit, how far `currentlyAvailable` falls and how long it takes to refill.
Those numbers belong in `010` and `020` as the answer to their open questions, and in
`docs/FULFILLMENT_VERIFICATION.md` if the shipment sync turns out to be the expensive one.


## Sources

- [Shopify API limits](https://shopify.dev/docs/api/usage/limits): the cost table, the 1,000-point single-query
  cap, the per-plan restore rates, and the `extensions.cost` fields. Checked 2026-09-16; the connection formula
  is not published there.
