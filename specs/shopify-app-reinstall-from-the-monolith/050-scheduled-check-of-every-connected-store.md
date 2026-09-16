# Spec 050: a scheduled check of every connected store

Depends on: `030` (`checkStoreShopifyStatus`), `040` (the "Check all" button enqueues the same job).
Repos: monolith only. Optional: `000`'s open question 7.


## What changes

- **Job `CheckStoreShopifyStatus(storeId)`** (`job/`, a `PgmqHandler`): runs `checkStoreShopifyStatus` for one store
  with `source = scheduled_check` (or `manual_check` when the message says so: a `source` field on the message, set by
  the "Check all" button). A DSS that did not answer is `retryUnlessRefused` like the other DSS jobs: three attempts,
  then the dead-letter queue, with the row saying `unknown` meanwhile. A `401` or `403` is not a job failure: it is
  the answer, recorded.
- **Job `EnqueueShopifyStoreChecks`**: one message per store with `encoded_api_key is not null`, enqueued by a
  `pg_cron` schedule once a day at a quiet hour (`supabase/migrations/…_daily_shopify_store_checks_cron.sql`, upserted
  by name like the other schedules). One fan-out job rather than one cron row per store, so a store created today is
  checked tomorrow without a migration.
- **Never-connected stores** are not checked: the DSS would answer `401` for every one of them every day, and the
  answer is already known from `encoded_api_key`.


## Behavioral contract

- Every store with a token gets a status row no older than a day, whatever a human did.
- A store whose token Shopify rejected is flagged within a day even if no order, shipment or tracking event touched it
  (the check's `TokenRejected` goes through `030`'s report path on the DSS, so the notification goes out).
- Two overlapping checks of one store (the cron and a "Check now") both write the row; the later `checked_at` wins.


## Edge cases

- The DSS is redeploying at the scheduled hour: every check fails transport, retries with backoff, and most succeed on
  the second attempt; the ones that do not sit in the dead-letter queue with the row at `unknown`, and the next day's
  run fills them. Nothing alerts on a single `unknown`; `040`'s admin page shows them.
- A retailer with many stores: the fan-out is sequential per PGMQ listener; with the current listener count and the
  DSS's per-call cost (two Shopify queries), a hundred stores take a few minutes. Acceptable at the expected scale.


## Reuse inventory

- `SyncShipmentsWithFulfillments` as the job pattern; `pgmqEnqueue`; the cron migrations
  (`20260530140000_daily_psp_wrap_up_cron.sql`) for the schedule and the fan-out message.


## Test plan

- `CheckStoreShopifyStatusDbTest`: the row after each DSS answer; a transport failure throws for the retry; a `401`
  records `missing` and does not throw.
- `EnqueueShopifyStoreChecksDbTest`: one message per connected store, none for a store without a token.
- The cron row: covered by the existing migration test that lists `cron.job` (if there is one; otherwise a `DbTest`
  that asserts the job name exists after the migrations ran).
