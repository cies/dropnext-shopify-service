# Spec: reuse an existing fulfillment event instead of creating a duplicate

Status: draft
Author: cies (with Claude)
Date: 2026-09-30
Depends on: nothing.
Repos: DSS only. No contract change: `POST /tracking-updates` keeps its request and its response, so the monolith's
`ForwardTrackingUpdateToDss` and the checked-in `monolith-dss-openapi.json` stay as they are.


## Problem

The monolith forwards every AfterShip checkpoint to the DSS through its `ForwardTrackingUpdateToDss` job, and
`syncShopifyTrackingEvent` turns it into a Shopify `fulfillmentEventCreate`. That mutation creates a new event
every time it is called: nothing on either side checks whether the fulfillment already has it.

The monolith's queue delivers a job at least once, so the same checkpoint reaches the DSS more than once when:

1. **The DSS answered, and the monolith lost the answer.** Shopify created the event, but the monolith saw a
   `5xx` or its 30-second read timeout, threw, and retried the job. This is the likely case.
2. **The monolith finished the job and then failed to archive its message**, so the message is delivered again.
3. **The monolith's process died after the call** (a deploy waits only seconds for running jobs, or it ran out
   of memory), so the message reappears when its lease runs out.

The monolith deduplicates AfterShip's webhook deliveries on their `event_id` (`processTrackingUpdate`), but that
only stops a second *enqueue*. It does nothing for a job that runs twice. The monolith cannot close the gap on its
own either: a record of "forwarded" written after the call is lost in exactly the crash of case 3.

**The effect:** the fulfillment shows the same event twice, on the order's timeline in the Shopify admin and on
the customer's order status page. UNVERIFIED: whether Shopify also sends the customer a second shipping-update
notification ("out for delivery", "delivered") for the duplicate. If it does, this is visible in the customer's
inbox as well.


## What changes

- **A new query, `src/resources/GetFulfillmentEvents.graphql`**, reads one fulfillment's most recent events:

  ```graphql
  query GetFulfillmentEvents($id: ID!, $eventsFirst: Int!) {
    fulfillment(id: $id) {
      events(first: $eventsFirst, sortKey: HAPPENED_AT, reverse: true) {
        nodes { id status happenedAt }
      }
    }
  }
  ```

  A separate query rather than more fields on `GetOrderForDss`. That query serves the order ingest and the shipment
  sync as well, and asking every fulfillment of every order for its events would raise the cost of each of those
  calls to benefit this one path. `GetFulfillmentContent` set the precedent of a query per fulfillment.
- **`ShopifyGraphqlService.fulfillmentEvents(fulfillmentGid)`** answers the events as a small domain list of
  (id, status, happened at). It is implemented in `HttpShopifyGraphqlService` and faked in `FakeShopifyGraphqlService`.
- **`syncShopifyTrackingEvent`** reads the fulfillment's events once it has found the fulfillment. If one has the
  same status and happened at the same moment, it answers that event's id and does not call
  `fulfillmentEventCreate`. Otherwise it creates the event as it does today.
- **One log line** when an existing event is reused, so that duplicates stay countable in Logflare:
  `Tracking event already on fulfillment fulfillmentId=… eventId=… status=…`.


## Behavioral contract

- **The match is on status and `happenedAt`**, compared as instants truncated to whole seconds. Shopify stores
  `happenedAt` at its own precision, and AfterShip's checkpoint time may carry a zone offset or fractions; the
  request's `happened_at` is parsed, not compared as text.
- **The message is not part of the match.** It is free text, and AfterShip may redeliver a checkpoint with its
  message reworded. Two checkpoints with the same status at the same second are, for the customer, one event.
- **The answer does not change.** A reused event is answered exactly like a created one: `200` with its
  `fulfillment_event_id`. The monolith cannot, and does not need to, tell the two apart.
- **A failure to read the events fails the call**, as a failure of `orderForDss` does today, so the monolith
  retries the job. Creating the event anyway when the read fails would bring the duplicate back through the
  side door.


## Edge cases

- **More events than the page holds.** The query asks for the newest events first (`HAPPENED_AT`, reversed) with a
  page size of 50. A redelivered checkpoint is recent, so it is on that page; an older duplicate beyond it is
  created again. A fulfillment with more than 50 events is not a case that exists in practice. The page size gets a
  named constant with that reasoning, beside the other page sizes in `HttpShopifyGraphqlService`.
- **Two deliveries at the same moment.** Two tasks, or two monolith workers, can both read "not there" and both
  create the event. This spec narrows the window from "every redelivery" to "two calls inside a few hundred
  milliseconds of each other", which the monolith's queue makes rare: a job's retry waits at least ten seconds.
  Closing it fully would need a lock across DSS tasks, which the DSS has no place to keep.
- **The fulfillment was canceled between the two calls.** `liveFulfillmentsWithTrackingNumber` already leaves it
  out, so the call answers `NotFound`, as it does today.
- **An unsupported status.** Still refused before any Shopify call, as today.


## Reuse inventory

- `liveFulfillmentsWithTrackingNumber` (`domain/fulfillment/`) finds the fulfillment, unchanged.
- `HttpShopifyGraphqlService.execute` and its `ShopifyError` mapping serve the new query like every other.
- `fulfillmentContent` is the model for a query per fulfillment, including its `NotFound` for a missing one.
- `FakeShopifyGraphqlService` gains a `fulfillmentEventsResult` alongside `createFulfillmentEventResult`.
- `test/.../SyncShopifyTrackingEventTest.kt` and its fixtures (`orderWithFulfillment`, `fulfillment`) are extended
  rather than replaced.


## Tests

In `SyncShopifyTrackingEventTest`:

- An event with the same status and `happenedAt` already on the fulfillment: its id is answered, and
  `createFulfillmentEventCalls` stays empty.
- The same status at a different time, and a different status at the same time: the event is created.
- The same moment written differently (`2026-09-30T10:00:00Z` against `2026-09-30T12:00:00+02:00`, and with
  fractional seconds): recognized as the same event.
- Reading the events fails (a transport error, and a throttle): the call fails with that error and nothing is
  created.

A wire-level test for the new query in the style of the existing `HttpShopifyGraphqlService` tests: the variables
it sends, and the events it decodes from a recorded response.


## Out of scope

- **The monolith.** Nothing changes there. Its job keeps retrying on failure, which after this spec is safe.
- **Shipment sync fulfillments.** Those are already reconciled by tracking number in
  `determineShopifyMutations`; this spec is about tracking events only.


## Open questions

1. Does Shopify send the customer a notification for a duplicate fulfillment event? It decides how urgent this
   spec is, not what it does.
2. Is 50 the right page size? It only needs to exceed the number of checkpoints one parcel can collect between a
   first delivery and its redelivery.
