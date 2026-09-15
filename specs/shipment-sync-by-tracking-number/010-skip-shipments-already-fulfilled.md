# Spec: skip shipments that already have a fulfillment

Status: draft
Author: cies (with Claude)
Date: 2026-09-11
Depends on: `specs/matcher-cleanup/010-one-walk-per-variant.md` (the plan carries the skips). Prerequisite for
`020` and `030`.
Repos: DSS only.


## Problem

`syncShopifyShipmentsToFulfillments` matches every shipment in the payload against the live remaining
quantities of the order's fulfillment-order lines. It never asks whether the shipment's tracking number is
already on a fulfillment of that order, although `GetOrderForDss` loads `fulfillments { trackingInfo { number } }`
for the tracking-update flow.

So a re-sent shipment is re-created whenever remaining quantity is left. With an order of 2 × variant X,
shipment A (1 × X) synced, and the payload `[A, B]` sent again after a partial failure, A gets a second
fulfillment and B is refused with "exceeds remaining 0". The monolith re-sends in exactly these cases: a run
whose POST succeeded but whose commit did not, and a retry after the DSS answered a 5xx part-way through the
creates.

The monolith's comments already assume that the DSS keys the sync on the tracking number (`000`, "What the
monolith already promises"), and `docs/FULFILLMENT_VERIFICATION.md` lists the duplicate as a known gap.


## What changes

- `src/resources/GetOrderForDss.graphql`: the `fulfillments` block gains `status`. A cancelled fulfillment
  keeps its tracking number and stays in the list; it must not count as "already fulfilled", or the split
  flow of `020` could never re-create a replaced tracking number.
- `domain/fulfillment/`: one lookup, "the live fulfillments of this order that carry this tracking number". It
  trims both sides, compares case-sensitively, and ignores fulfillments whose status is `CANCELLED`. The sync and
  `syncShopifyTrackingEvent` both use it; see "Edge cases" for what that changes on `/tracking-update`.
- `domain/fulfillment/`: a shipment-level skip, decided before the quantity matching runs. A shipment whose
  tracking number the lookup finds is skipped as a whole, with a new reason `ALREADY_FULFILLED`. It contributes
  nothing to the plan, so its lines cannot make a later shipment in the same payload fail for want of quantity.
- `workflow/calculateShopifyMutations.kt`: no `FulfillmentCreate` for a skipped shipment. The skip travels in the
  plan that `matcher-cleanup/010` introduces, beside the skipped lines.
- `workflow/determineShopifyMutations.kt`: the skip log line gains `reason=already_fulfilled` and the existing
  fulfillment's id, so a Logflare search by tracking number finds the fulfillment it landed on.
- `workflow/syncShopifyShipmentsToFulfillments.kt`: the summary line's `skippedShipments` is read from the plan
  (the shipments skipped by tracking number plus `unmatchedShipments`) instead of computed as the payload size
  minus the creates. The two agree today; once there are two kinds of skipped shipment, the plan is the one source.
- Response: unchanged in this spec. A skipped shipment is simply absent from `new_fulfillment_ids`; `030`
  makes it visible.


## Behavioral contract

- **Precondition**: a valid `SyncShipmentsWithFulfillmentsRequest` (the validators already refuse a duplicate
  tracking number inside one payload).
- **Postcondition**: after a successful run, every shipment in the payload that matched at least one line has its
  tracking number on a live fulfillment of the order, whether this run created it or it was there before. A
  shipment whose lines all went unmatched has no fulfillment, as today (`all_lines_unmatched`).
- **Invariant**: this service never puts a tracking number on a second live fulfillment. A re-send of a payload
  whose shipments all have live fulfillments plans nothing, calls no mutation, and answers `200` with an empty
  `new_fulfillment_ids`.
- **Ordering**: the tracking-number check runs before quantity matching; a shipment that is skipped for this
  reason is never reported as a quantity error.


## Edge cases

- **Tracking number on a cancelled fulfillment only**: not skipped; matched and created like a new shipment.
  This is the state `020` produces on purpose.
- **Tracking number on a live fulfillment plus remaining quantity elsewhere**: skipped. The remaining
  quantity belongs to whatever ships next.
- **Two live fulfillments carry the same tracking number** (a merchant did that by hand, or an earlier
  duplicate from before this spec): skipped, logged at `warn` with both fulfillment ids. Cleaning that up is
  a human's job.
- **Whitespace and case**: the comparison trims both sides and is case-sensitive, matching how the monolith
  stores tracking numbers. Not lower-cased: carriers differ on whether case is significant.
- **A fulfillment with several tracking numbers**: any of them matching counts. Two shipments of one payload can
  therefore both be skipped onto that one fulfillment; `030` reports both with its id.
- **Mixed payload**: `[already fulfilled, new]` plans one create and logs one skip.
- **A retry after a partial failure**: the monolith re-sends the whole payload after a `502`. The shipments
  whose creates landed before the failure now have live fulfillments and are skipped as `ALREADY_FULFILLED`;
  the one whose create failed, and any after it, are matched and created. This is what makes the re-send safe,
  and what `030` reports as `already_fulfilled` with the existing fulfillment's id. A create that failed on the
  wire may still have created the fulfillment on Shopify's side; the retry finds its tracking number and
  skips it too, which is the honest answer, since the id was never lost, only unknown.
- **`/tracking-update` through the shared lookup**: it gains the trim, and it no longer picks a cancelled
  fulfillment. Today it takes the first fulfillment of the order whose tracking numbers contain the exact number,
  cancelled or not; after `020` a tracking number reused by a split sits on a cancelled fulfillment and on its live
  successor, and the event must land on the live one.


## Reuse inventory

- `order.fulfillments[].trackingInfo[].number` is already loaded and read by `workflow/syncShopifyTrackingEvent.kt`
  (an exact comparison over every fulfillment); the shared lookup replaces that `find`.
- `SkipReason` and `SkippedShipmentLine` in `domain/fulfillment/matchShipmentToFulfillmentOrders.kt`: the new
  reason is shipment-level, not line-level, so either widen `ShipmentMatchResult.Ok` with a
  `skippedShipment: SkipReason?` or add a sibling type. Prefer the former: one result per shipment, in payload
  order, which `030` needs anyway.
- `orderWithFulfillment(id, trackingNumbers)` in `test/dropnext/dss/testutil/fixture/OrderFixtures.kt` builds
  the fixture; it needs a `status` parameter once the query carries it.
- The skip log format that `matcher-cleanup/010` moves to read the plan.


## Test plan

Test-first, cheapest flavour first.

- **Pure** (a test beside the lookup, and `MatchShipmentToFulfillmentOrdersTest` for the skip): live fulfillment
  with the tracking number → skipped, nothing planned for it; cancelled fulfillment with it → not skipped; two live
  fulfillments with it → skipped; trimmed match; a fulfillment with two tracking numbers.
- **Fake-backed** (`CalculateShopifyMutationsTest`, `SyncShopifyShipmentsToFulfillmentsTest`): the re-send
  scenario from "Problem" with remaining quantity left → one create for B, none for A; an all-fulfilled
  payload → no mutation and an empty plan; the summary log line counts the skip.
- **Request → response** (`MonolithWebhookHandlersTest`): the same re-send through `withDssApp` answers `200`
  with `new_fulfillment_ids` holding only B's id, and the fake records a single `FulfillmentCreateWithLineItems`;
  `tracking-update` for a tracking number that sits on a cancelled and on a live fulfillment attaches the event to
  the live one.
- **Wire** (`HttpShopifyGraphqlServiceTest`): the `orderForDss` case's response carries a fulfillment `status`
  and it deserializes.
- The existing test `partial failure recovery on retry skips the fulfilled variant and creates the rest` keeps
  passing, now because the fulfilled shipment is skipped by its tracking number; rename it to say so.


## Open questions for the human developer

1. Should the skip be a `warn` or an `info`? A re-send is expected behaviour of the monolith, which argues for
   `info`; a tracking number that was already there when it was *not* a re-send is a data problem, which the
   DSS cannot tell apart.
2. Is case-sensitive comparison right for our carriers? The monolith keeps tracking numbers unique in
   `tracking_numbers`; whether it normalises case on entry decides it.
