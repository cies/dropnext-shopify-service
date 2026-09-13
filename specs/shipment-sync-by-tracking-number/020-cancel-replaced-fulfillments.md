# Spec: cancel the fulfillments the monolith replaced

Status: draft
Author: cies (with Claude)
Date: 2026-09-11, revised 2026-09-11 (Shopify puts cancelled items on new fulfillment orders, so the creates are
planned after a reload; the new request field is optional).
Depends on: `010`. A monolith spec in `../dropnext-monolith/specs/` for the payload and the job has to exist and be
approved before this one; there is none yet.
Repos: monolith first, then the DSS in the same session (`../CLAUDE.md`, "Cross-repo couplings").


## Problem

The supplier portal's shipment split marks the old shipments `replaced_at`, inserts the new ones and
enqueues the sync. The DSS receives only the new shipments, finds that the old fulfillments still hold the units,
skips every line, and answers `200` with nothing created. Shopify keeps the old tracking numbers; the next
`/tracking-update` for a new number answers `404`. Nothing errors, and the supplier believes Shopify was updated.
Details and the sequence diagram are in `000-analysis-cancel-and-recreate.md`.

Cancelling every fulfillment (the July design) is not the answer either: the monolith only ever sends
unsynced shipments, so cancel-all destroyed earlier, still-valid fulfillments whenever a supplier added a
later shipment. The DSS has to be told which fulfillments to cancel, and the monolith knows: the shipments
with `replaced_at` set.


## What Shopify does on a cancel

Shopify's documentation of `fulfillmentCancel` says that a cancel "reverses its effects on associated
FulfillmentOrder objects" and that "the system creates new fulfillment orders for the cancelled items so they can
be fulfilled again": at the original location when the items are still stocked there, otherwise at another
location chosen by the store's fulfillment priority. For a fulfillment order that was partially fulfilled, "the
remaining quantities adjust to include the cancelled items".

The consequence: the fulfillment-order lines that a create after the cancel has to name may not exist before the
cancel, and their ids cannot be known in advance. The first draft of this spec credited the cancelled quantities
back to the open lines of the order loaded before the cancel, and planned every create up front. In the split
scenario that cannot work: the old fulfillments completed the fulfillment order, which Shopify closes once it is
completed, so the snapshot has no open line to credit, and the units come back on a fulfillment order the snapshot
does not contain. This spec therefore cancels, reloads the order, and plans the creates against what Shopify made
of it. What can still be checked before the first mutation is whether the new shipments fit, per variant.


## What changes

### Contract (monolith serves it, DSS checks in the copy)

`SyncShipmentsWithFulfillmentsRequest` gains `replaced_tracking_numbers`, an array of strings, declared the way
`Shipment.carrier` is: not in `required`, and nullable.

- The monolith always sends it, as an empty list when nothing was replaced; the DSS reads an absent key or `null`
  as an empty list.
- It is not required because the generator turns a required property into a non-null Kotlin property without a
  default (as `shipments` is), and a request that lacks it would then fail to decode: a `400` for a monolith that
  predates the field. A property that is not required and nullable decodes as `null` when the key is absent,
  because `AppJson` has `explicitNulls = false`; the existing test
  `sync-shipments accepts a body without the nullable carrier and tracking_url keys` pins that behaviour.
- In the other direction `AppJson` has `ignoreUnknownKeys = true`, so a DSS that predates the field keeps working
  while the monolith deploys first.
- The example in `SyncShipmentsWithFulfillmentsRequest.example` shows one replaced number.

### Monolith

- `db/sql/job/syncShipmentsWithFulfillmentsRead.kt`: `selectSyncShipmentsWithFulfillmentsPayload` also selects
  the tracking numbers of shipments of the same `shopify_order_id` with `replaced_at is not null`, and puts
  them in the request. All of them, every run: the DSS plans a cancel only for a fulfillment that is still live on
  the order it loads (the lookup of `010` ignores `CANCELLED` ones), so repeating the list is free and makes a retry
  after a failed cancel converge.
- The job's "nothing left to sync" no-op must not swallow a pending cancel: a run with no unsynced shipments
  but with replaced ones that were never mirrored still has to POST. The simplest rule that gives this is to
  stamp replaced shipments too, in a `replacement_synced_at` (or reuse `synced_at`, which a replaced shipment
  already has set: then the trigger is "replaced after the last sync"). The monolith spec decides; the DSS side
  only needs the list.
- The monolith spec also confirms that a synced shipment's lines change only through a split (see `000`, "What the
  monolith already promises").
- The split page copy says the split "will cancel the current shipment(s) and create new ones", which becomes
  true. Its claim about shipping-notification e-mails stays false while `notifyCustomer` is `false`; see the
  open questions.

### DSS

- The domain validators accept the field: strings, trimmed, non-blank, no duplicates; a replaced number that
  also appears in `shipments` is allowed (a supplier re-used a tracking number after a split).
- **Before any mutation**, `calculateShopifyMutations` works on the loaded order and plans:
  1. the cancels: one `FulfillmentCancel` per live fulfillment (the lookup of `010`) that carries a replaced
     tracking number, in the payload order of the numbers;
  2. a feasibility check per variant, over the shipments `010` does not skip: the quantity they ask for must not
     exceed the variant's live remaining quantity on open fulfillment-order lines plus the quantity the planned
     cancels free (the cancelled fulfillments' `fulfillmentLineItems` of that variant). The matcher's
     skip-versus-error rule applies to the totals: a variant with nothing remaining and nothing freed is skipped as
     today, a variant with some but not enough is the `UserError`, a `400` before the first cancel;
  3. when there is no cancel, the creates, exactly as after `010`: one load of the order, and nothing else changes
     for a payload without replaced tracking numbers.
- **After the cancels**, `syncShopifyShipmentsToFulfillments` runs them through `effectShopifyMutations`, reloads
  the order with `orderForDss`, plans the creates against the reloaded order with `010`'s skip and the matcher, and
  runs them. A replaced tracking number that is also in `shipments` is on a `CANCELLED` fulfillment by then, so
  `010` does not skip its shipment.
- `GetOrderForDss.graphql` adds, under `fulfillments` and beside the `status` of `010`:
  `fulfillmentLineItems(first: 100) { pageInfo { hasNextPage } nodes { quantity lineItem { variant { legacyResourceId } } } }`.
  The feasibility check needs quantities per variant and nothing else. The connection asks for `pageInfo` per
  `specs/paged-graphql-connections/010-fail-loudly-on-a-truncated-connection.md`: a fulfillment with more than 100
  lines is `Truncated("fulfillments.fulfillmentLineItems", 100)`. If that spec has not landed yet, this one adds the
  check for this connection itself.
- `effectShopifyMutations` stops at the first failure as today, but answers what it did before stopping: the
  cancelled ids and the created ids so far, beside the failure (`030` defines the shape). The summary log line's
  `canceled=` count becomes meaningful again.
- Failures:
  - A `ShopifyError.UserError` from a cancel (Shopify refused it and did not report the fulfillment as cancelled)
    fails the sync as a `400`, exactly as a refused create does: no later cancel and no create is sent, the cancels
    before it stay done, the monolith drops
    a `4xx` for good, and a human looks at the log.
  - A transport or Graphql failure of a cancel, of the reload or of a create is a `502`; the monolith retries with
    the same `replaced_tracking_numbers`, and the retry converges (see "Edge cases").
  - The plan after the reload fails (Shopify put the units where the feasibility check did not foresee them, or the
    order changed in between): a `400` logged at `error`, with the replaced fulfillments cancelled and nothing
    created. The new shipments stay unstamped; the next run plans no cancel (the fulfillments are `CANCELLED`) and
    plans the creates again. Open question 1 asks whether a `502` is the better answer.


## Behavioral contract

- **Precondition**: `010` is in place, so a re-sent shipment cannot be created twice.
- **Postcondition**: after a successful run, no live fulfillment of the order carries a replaced tracking number,
  and every shipment in the payload that matched at least one line has a live fulfillment.
- **Invariant**: the DSS cancels nothing it was not named. A fulfillment a merchant created by hand in Shopify, or
  one belonging to a shipment the monolith still considers live, is never touched.
- **Validate before mutate, per variant**: no cancel is sent unless the new shipments fit, per variant, into what
  remains plus what the cancels free.
- **Idempotence**: a second identical request plans no cancel (the fulfillments are `CANCELLED`), so it does not
  reload, plans no create (`010`), and answers `200`.


## Edge cases

- **Replaced number not on any fulfillment**: nothing to cancel; logged at `info`. The shipment may never
  have been mirrored (it was replaced before its first sync).
- **Replaced number only on a cancelled fulfillment**: nothing to cancel, no log line.
- **Replaced number also in `shipments`** (re-used tracking number): the cancel runs first; after the reload the
  only fulfillment with that number is `CANCELLED`, so `010`'s skip ignores it and the create proceeds.
- **The cancelled units come back at another location** (the original one is out of stock): the matcher does not
  look at locations; the create names whichever fulfillment-order line holds the units after the reload.
- **A cancel frees quantity the new shipments do not use**: that quantity stays unfulfilled in Shopify, which is
  the truth.
- **Shopify refuses a cancel** (a fulfillment already delivered, for example): `400`, nothing created. The order is
  left as it was only when the refused cancel is the first one; the cancels before it stay done, and `030` reports
  them. Open question 2 asks whether the creates should run anyway.
- **Partial failure** (cancel done, create failed): the retry re-sends the same payload with the same
  `replaced_tracking_numbers`; the cancel finds the fulfillment already `CANCELLED` and plans nothing for it,
  the creates that landed are skipped as already fulfilled (`010`), the failed create is attempted again. No
  cancel is repeated and no duplicate can arise. The effect step answers the cancelled ids and the created ids
  up to the failure together with the failure (`030`, "A run that fails part-way"), so the monolith's log of the
  failed run still says which fulfillments were cancelled.
- **The replaced fulfillment carried tracking events**: they are lost with the cancel. Same as the July design;
  noted in the docs (`040`).
- **`/tracking-update` for a re-used tracking number after the split**: `010`'s lookup ignores the cancelled
  fulfillment, so the event lands on the new one.


## Reuse inventory

- `ShopifyMutation.FulfillmentCancel`, the cancel branch of `effectShopifyMutations`,
  `ShopifyGraphqlService.cancelFulfillment` and `FulfillmentCancelMutation.graphql`: all present, all
  tested, currently without a producer. This spec is the producer.
- `HttpShopifyGraphqlService.cancelFulfillment` succeeds when Shopify's answer carries the fulfillment as `CANCELLED`,
  whatever user error comes with it, and answers any other refusal as a `UserError`. It does not read Shopify's
  wording, so it is the plan that keeps an already-cancelled fulfillment from being cancelled again (see "Idempotence").
- `ShopifyGraphqlService.orderForDss` for the reload; `FakeShopifyGraphqlService.orderForDssResultQueue` serves the
  order before and after the cancels.
- The live-fulfillment-by-tracking-number lookup and the shipment skip from `010`.
- `FulfillmentQuantityLedger`'s `init`, which reads the live remaining quantity of every open line, for the
  per-variant totals of the feasibility check.
- `validateSyncShipmentsRequest.kt`'s `validateDuplicateTrackingNumbers` shows the validator style for the new list.
- Monolith: `db/sql/portal/supplier/shipmentSplitWrite.kt` sets `replaced_at`; `shipmentSplitRead.kt` already
  filters on it, so the column and its meaning exist.


## Test plan

- **Pure**: the cancel plan for live, cancelled and absent tracking numbers; the feasibility check: the split
  scenario fits (2 × X fulfilled under A with nothing remaining, payload `shipments=[B(1×X), C(1×X)]`,
  `replaced=[A]`), one unit more does not (a `UserError`, no mutation planned), and a variant with nothing remaining
  and nothing freed is skipped.
- **Fake-backed** (`SyncShopifyShipmentsToFulfillmentsTest`):
  - the split scenario, with `orderForDssResultQueue` serving the order before the cancel (A's fulfillment order
    closed) and after it (a new fulfillment order with 2 × X remaining): cancel A, reload, then create B and C
    against the new fulfillment order's line, in that order;
  - a payload without replaced tracking numbers loads the order once;
  - a refused cancel stops before any create; with two replaced numbers, a refused second cancel leaves the first
    done and reported;
  - a plan that fails after the reload is a `UserError`, with the cancel done;
  - the summary line counts `canceled=1`.
- **Request → response** (`MonolithWebhookHandlersTest`): the split scenario through `withDssApp`; a payload
  without `replaced_tracking_numbers`, and one with `null`, decode and behave as an empty list; a blank replaced
  number is a `400`.
- **Wire**: `orderForDss` deserializes `status` and `fulfillmentLineItems` with its `pageInfo`.
- **Monolith** (`*DbTest` for the read query, the job's `*Test`): replaced shipments of the order appear in the
  payload; a run with only replaced shipments still POSTs.
- **Shopify, by hand on the staging shop**: split a synced order and confirm how the cancelled units come back (a
  new fulfillment order, or adjusted remaining quantities on an existing one). The design reloads either way, but
  the order the fake serves after the cancel must mirror what Shopify really does.


## Open questions for the human developer

1. When the plan after the reload fails, answer `400` as written (the monolith does not retry; the next run of the
   job plans again) or `502` (the monolith's pgmq retries it until the dead-letter queue)?
2. When Shopify refuses a cancel, should the DSS still create the new shipments' fulfillments where quantity
   allows, or fail the whole request as written here? Failing keeps the order in a state the log explains.
3. Should `notify_customer` become a request field, so the split page's e-mail claim can be made true, or
   should the page stop promising e-mails? Out of scope for the DSS either way; it needs a decision on the
   monolith side.


## Sources

- [Shopify Graphql Admin API: `fulfillmentCancel`](https://shopify.dev/docs/api/admin-graphql/latest/mutations/fulfillmentCancel):
  what a cancel does to fulfillment orders.
