# Shopify webhook deduplication: analysis and design

Status: draft analysis. No numbered specs yet: they follow once the open questions below are decided.
Author: cies (with Claude)
Date: 2026-05-24, revised 2026-09-11 (moved into this folder; brought up to date with Shopify's current retry
policy, the webhook handler's outcome-based answers and a review of Caffeine as a dependency).
Repos: DSS; the monolith for layer 2.


## What runs today

- `ShopifyWebhookHandlers.handleShopifyWebhook` verifies the HMAC, runs one workflow per topic and chooses the
  answer from its `WebhookMirrorOutcome`: a `200` when a redelivery could not go better, a `502` when the outcome
  `isTransient` (Shopify or the monolith did not answer, throttled, answered a `5xx`, or the work did not finish
  within the handler's four-second budget), so that Shopify's own redelivery is the retry.
- The mirrored topics, and how the monolith absorbs a duplicate of each:
  - `orders/create` → `POST /orders`: a second create is a `409`, read as `CreateOrderOutcome.AlreadyExisted`,
    which is a success.
  - `products/create` and `products/update` → the variant upsert: repeating it with the same content is a no-op.
  - `products/delete` → the variant soft-delete: idempotent.
  - `orders/updated` is acknowledged and not mirrored. The `DSS_SYNC_ORDER_ON_UPDATED` switch that the first draft
    of this document discussed was removed in June 2026.
- Every verified delivery ends in one `Webhook done … webhook_id=… outcome=…` line (`WebhookDeliveryReport`). The
  same `webhook_id` on two lines is a redelivery, so "how many duplicates did we receive" is already a Logflare
  query; what is missing is not paying for them.
- The DSS runs as one Fargate task (`enable_autoscaling = false` in `dropnext-infra/modules/app-dss-stack`), but a
  deploy starts the new task before it stops the old one (`dropnext-infra/modules/fargate-service/main.tf`), so two
  tasks serve side by side for a short while, and every deploy starts with empty memory.


## Problem

Shopify guarantees at-least-once delivery. The same delivery reaches `/webhooks/shopify` more than once when:

1. **Shopify retries.** A delivery fails when the app does not answer within five seconds; Shopify then retries
   it up to eight times in four hours, with a growing interval. After repeated failures within 24 hours it removes
   the subscription, which `/api/check` then reports as `missing`.
2. **We ask for it.** The handler answers `502` to a transient failure precisely so that Shopify redelivers.
3. **Two tasks serve at once**: during a deploy today, and all the time once the service scales out.
4. **An operator resends** a delivery from the Partner Dashboard during incident recovery.

Separately, a shop with two subscriptions to one topic at our callback URL receives one delivery per subscription.
`registerShopifyWebhooks` registers one per topic, so that only happens through a subscription made by hand.


## The reason why we have not implemented this, yet...

The *correctness* defect is absorbed by the monolith's idempotency for every topic mirrored today. The *efficiency*
defect is real but modest: each duplicate `orders/create` costs a `GetOrderForDss` and a monolith round trip, each
duplicate product webhook a `GetProductById` and an upsert, and Shopify's retries are eight per failure. What is
missing is a *floor*: a future topic whose effect is not idempotent in the monolith (`app/uninstalled`, the GDPR
topics) has nothing that stops a second effect.


## Goals

1. **At-most-once effects** in the monolith for any given Shopify delivery, even across two tasks.
2. **Cheap to add**: the DSS keeps no database, and adopting one for deduplication alone would invert its
   operational model.
3. **Observable**: every dropped duplicate shows in the existing delivery line, countable by reason.
4. **Recoverable**: a delivery whose effect did not reach the monolith is never dropped as a duplicate, whether
   Shopify retries it or an operator resends it.


## Non-goals

- **Ordering guarantees.** Shopify does not deliver in order and we do not promise to process in order; the
  monolith resolves conflicts by `shopify_order_id` and timestamps.
- **End-to-end exactly-once.** That would need a two-phase commit across the DSS and the monolith; at-most-once at
  the deduplication boundary plus the monolith's idempotency is the target.


## What Shopify gives us

- **`X-Shopify-Webhook-Id`** identifies one delivery of one subscription and stays the same across its retries.
  Shopify recommends it for deduplicating deliveries, and its recipe checks a store for the id, skips a known one,
  and saves the id *after* processing.
- **`X-Shopify-Event-Id`** is the same across the deliveries of every subscription that one merchant action
  triggered. Shopify recommends it for correlating those deliveries, not for deduplicating one.
- **`X-Shopify-Triggered-At`** says when Shopify generated the event; the delivery line already reports the lag
  from it.

The key is `X-Shopify-Webhook-Id`. A key on topic and resource id would also swallow a later, genuine change to the
same resource (a second `products/update` is not a duplicate), and `X-Shopify-Event-Id` would merge the deliveries
of two subscriptions, which only a subscription made by hand produces.


## When a delivery counts as seen

The first draft recorded a delivery when it was accepted, before its workflow ran. With the handler's current
answers that loses events:

1. Delivery `d` arrives and is recorded, and the monolith is down: the outcome is transient, the answer a `502`.
2. Shopify redelivers `d` with the same `X-Shopify-Webhook-Id`. The cache knows the id and answers `200` without
   doing any work.
3. Shopify stops retrying. The order never reaches the monolith.

The rule instead, which is also Shopify's recipe:

- **Record a delivery only when its outcome is `Mirrored`.** A skipped or failed delivery is not recorded, so a
  retry or a resend runs it again, which is today's behaviour and safe by the monolith's idempotency. That
  includes the skips: a delivery skipped for `NO_ADMIN_TOKEN` must run again when an operator resends it after the
  app was reinstalled.
- **Mark a delivery in flight while it runs.** A duplicate can arrive while the first attempt is still working. The
  handler gives up on its work after four seconds, before Shopify stops waiting at five, so Shopify's own retry no
  longer finds an attempt still running; an operator's resend can. The duplicate is answered `502` without work, so
  that Shopify's next retry sees the settled state: recorded (then dropped) or cleared (then run). A delivery that
  ends in anything but `Mirrored` clears its mark. Such a `502` counts as one failed attempt towards the subscription
  removal; only a duplicate that lands within an attempt's four seconds can cause one.


## Approaches considered

### A. Trust the monolith (what runs today)

It works for every topic mirrored today, costs a Shopify read and a monolith round trip per duplicate, and gives a
future non-idempotent topic no floor.

### B. In-memory deduplication per task

A map from `X-Shopify-Webhook-Id` to its state (in flight, or mirrored at an instant), with a TTL.

- **Pros**: no infrastructure; covers Shopify's retries and operator resends on one task; small.
- **Cons**: empty after every deploy and every restart; the two tasks of a deploy do not share it.
- **TTL**: Shopify's retries end four hours after the first failure; 24 hours also covers a resend on the same day.
  At the volume of one merchant's orders and product edits the memory is negligible either way.

### C. Shared store (Redis)

The same state in a Redis instance every task shares. It survives deploys and coordinates tasks, but it adds
infrastructure the project does not have (an operations decision, not a Gradle one) and a failure mode of its own:
with Redis down, fail closed (`502`, Shopify retries) or fail open (process, and risk a second effect).

### D. Deduplication in the monolith

A `webhook_deliveries` table keyed on `(shop_id, webhook_id)`, owned by the monolith, which already owns the data.

- **Pros**: one source of truth; survives everything; the DSS stays stateless.
- **Cons**: a round trip per delivery; work in the monolith; no help for an effect that stays inside the DSS (there
  is none today).
- **The claim has the same trap as the first draft of B.** A pre-check that inserts an `in_flight` row and answers
  `409` on any collision makes the retry of a transiently failed delivery look like a duplicate. The rules have to
  be: a collision with a `processed` row is a duplicate (the DSS answers `200` without work); a collision with a
  fresh `in_flight` row means "try again later" (the DSS answers `502`); a delivery that does not end `Mirrored`
  releases its claim; an `in_flight` row older than a staleness window (about ten minutes) can be taken over after
  a crash. Marking the row `processed` needs either a second call from the DSS or the webhook id on the monolith
  write the delivery causes; open question 5 asks which.


## Recommended approach

**Layered: B in the DSS now, D in the monolith when it is needed.**

- **Layer 1, the DSS (B).** A `WebhookDeliveryLedger` beside the handler, consulted after the HMAC check and before
  the topic dispatch, keyed by `X-Shopify-Webhook-Id` and following "When a delivery counts as seen". A delivery
  without the header is processed as today. A dropped duplicate is the outcome
  `WebhookMirrorOutcome.Skipped(WebhookSkipReason.DUPLICATE_DELIVERY)`, so it is a `200` with
  `outcome=skipped reason=duplicate_delivery` in the existing delivery line and response body. An in-flight
  duplicate is a `502` whose delivery line says so.
- **Layer 2, the monolith (D).** Worth its round trip only when one of two things happens: a topic whose effect is
  not idempotent in the monolith gets mirrored, or the DSS runs more than one task outside deploys. Until then
  layer 1 plus the monolith's idempotency covers every case that exists.

The order of work, each step independent of the next:

1. **DSS, layer 1.** The ledger and its wiring in `ShopifyWebhookHandlers`.
2. **Monolith, layer 2**, once triggered. The table and the claim endpoint, specified in `../dropnext-monolith/specs/`
   first, per the workspace rule that the provider lands first.
3. **DSS, layer 2.** The pre-check between layer 1 and the topic dispatch.


## Dependency: Caffeine or a hand-written ledger

The first draft proposed Caffeine for layer 1 and guessed it was already on the classpath through OkHttp. It is
not: `./gradlew dependencies --configuration runtimeClasspath` lists no Caffeine artifact, so it would be a new
dependency, which needs human sign-off.

What adding it would mean:

- **Version**: 3.2.4 (May 2026), the latest release.
- **Transitive dependencies**: `org.jspecify:jspecify:1.0.0` and `com.google.errorprone:error_prone_annotations:2.49.0`,
  both annotation-only jars.
- **Reflection**: yes, internally. Caffeine generates one cache class per combination of features and picks the
  right one when a cache is built, by a computed class name: `LocalCacheFactory` calls
  `MethodHandles.lookup().findClass(…)` and then `findStaticVarHandle` or `findConstructor` on the class it found,
  and its node classes are loaded the same way (issue #552). That is reflective lookup in the sense our reflection
  policy means. It works on the JVM the DSS runs on; it is also what breaks GraalVM native images unless every
  generated constructor is registered for reflection, which the DSS does not use.
- **Quality**: mature, Apache 2.0, widely used (Spring Boot, among others, integrates it), with an eviction and
  expiration engine far beyond what layer 1 needs.

What layer 1 needs: a set of at most a few thousand ids a day, each with a state and a fixed TTL, on one task. No
size-based eviction policy, no loading on a miss, no refresh, no statistics. A `ConcurrentHashMap<String, Entry>`
that drops expired entries on write (and when it grows past a cap) is about thirty lines, takes an injected `Clock`
so the TTL is testable without sleeping, and needs neither a dependency nor reflection.

**Verdict: Caffeine is a good library and a poor fit here.** Do not add it for layer 1. Reconsider it when the
service needs a real cache, bounded by size, loading on a miss or refreshed in the background (a cache of Shopify
reads, for example), where its eviction engine earns the dependency and the reflection exception.


## Test plan (layer 1)

- **Pure** (`WebhookDeliveryLedgerTest`, with a fixed `Clock`): an unknown id is not a duplicate; a mirrored id is
  one until the TTL passes; an in-flight id reads as in flight; clearing an in-flight id makes it unknown again;
  expired entries are dropped on write.
- **Request → response** (`ShopifyWebhookHandlersTest`, through `withDssApp`):
  - `orders/create` delivered twice with one `X-Shopify-Webhook-Id`: the first mirrors, the second is a `200` with
    `reason=duplicate_delivery`, and `FakeMonolithService` records a single `postCreateOrder`.
  - The first delivery fails transiently (the fake monolith answers a `5xx`) and is answered `502`; the redelivery
    with the same id runs again and mirrors.
  - A delivery skipped for `NO_ADMIN_TOKEN` and redelivered after a token was added mirrors on the second run.
  - An HMAC failure does not touch the ledger.
  - A delivery without `X-Shopify-Webhook-Id` is processed as today, twice when sent twice.


## Open questions for the human developer

1. **Layer 2 outage.** When the monolith pre-check does not answer: fail closed (`502`, Shopify retries) or fail
   open (process, and risk a second effect)? Recommendation: fail closed, like the monolith write that would follow
   and would fail the same way. Keep in mind that repeated failures within 24 hours cost the subscription.
2. **Operator resends.** Does a resend from the Partner Dashboard keep the delivery's `X-Shopify-Webhook-Id`? Shopify
   does not document it; try it on the staging shop. Under "When a delivery counts as seen" either answer is safe: a
   resend of a mirrored delivery is dropped, a resend of a failed one runs.
3. **TTL.** Four hours (Shopify's retry window) or 24 hours (also same-day resends)? Memory does not decide it.
4. **Event id.** Log `X-Shopify-Event-Id` in the delivery line, so that a subscription made by hand shows as two
   deliveries of one event?
5. **Layer 2 bookkeeping.** Mark the monolith row `processed` through a second call from the DSS, or by passing the
   webhook id on the write the delivery causes?


## What this document does not cover

- **Outbound retries to the monolith.** `createMonolithHttpClient` retries a request that failed with an
  `IOException` up to three times; a monolith `5xx` makes the delivery transient, and Shopify redelivers it.
- **Shopify throttling.** It surfaces as a transient `ShopifyError.HttpError(429)` or `GraphqlError`, answered with a
  `502`; unchanged.
- **`app/uninstalled` and the GDPR topics.** Layer 2 is the floor they need; what they do (drop tokens, schedule
  deletion) is a design of its own.


## Sources

- [Shopify: troubleshoot webhooks](https://shopify.dev/docs/apps/build/webhooks/troubleshooting-webhooks): the
  five-second timeout, eight retries in four hours, removal of the subscription after repeated failures.
- [Shopify: ignore duplicate webhooks](https://shopify.dev/docs/apps/build/webhooks/ignore-duplicates):
  `X-Shopify-Webhook-Id` versus `X-Shopify-Event-Id`, and saving the id after processing.
- [Caffeine releases](https://github.com/ben-manes/caffeine/releases) and
  [Caffeine on Maven Central](https://central.sonatype.com/artifact/com.github.ben-manes.caffeine/caffeine): the
  latest version and its declared dependencies.
- [`LocalCacheFactory.java`](https://github.com/ben-manes/caffeine/blob/master/caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalCacheFactory.java):
  the lookup of a generated class by name.
- [Caffeine issue #552](https://github.com/ben-manes/caffeine/issues/552): the same lookup, for the cache and node
  factories, under GraalVM native image.
