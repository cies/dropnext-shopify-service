# Spec 030: the monolith learns of a broken installation

Depends on: `010` (the DTOs and the client), `020` (`AccessDenied`).
Repos: monolith first (the new endpoint, the changed DTO, the table, the job), then the DSS (the push, the changed
install), the checked-in spec copy in between.
Parallel: the monolith's table, endpoint and notification can be built while the DSS side of `010` is finished.


## What changes

Today the monolith's picture of a store's Shopify connection is one bit: `encoded_api_key is not null`. This spec
gives it a status row per store, filled from three sources, and one consequence: the retailer's users are told, once,
with the link that fixes it, and the refused work is retried after the fix.

### Monolith

- **Table `store_shopify_statuses`** (migration), one row per store, `store_id` primary key referencing `stores`:
  - `checked_at timestamptz not null`: when the row's facts were observed;
  - `source text not null`: `manual_check`, `scheduled_check`, `install`, `dss_report`;
  - `token_status text not null`: `valid`, `missing`, `rejected`, `unknown` (the DSS or Shopify did not answer);
  - `granted_access_scopes text[]`, `missing_access_scopes text[]` (null when unknown);
  - `webhooks_status text`: `complete` (every handled topic `active` and no obsolete row), `incomplete`, or null when
    not checked (an install report and a problem report say nothing about them; a check does);
  - `status_detail jsonb`: the last `ShopifyStoreStatusResponse` as received, for `040`'s detail view; null unless
    the source was a check;
  - `reauthorization_needed_since timestamptz`, `reauthorization_reason text`: set while the store needs the merchant
    (token rejected or missing, or a missing scope); cleared by a check or an install that finds the grant complete.
  Row-level security like `stores`: a retailer user reads their own retailer's rows.
- **`POST /stores/shopify-access-problems`** (`dssApiRouter.kt` `routes`, `handler/api/dss/`): receives
  `ShopifyAccessProblemRequest(shopify_subdomain, kind: ShopifyAccessProblemKind, missing_access_scopes: List<String>,
  observed_during: String, observed_at: String)` where `kind` is `token_rejected` or `access_scope_missing` and `observed_during` is a short
  label of the operation (`orders/create webhook`, `sync-shipments-with-fulfillments`, `tracking-update`,
  `shopify-store-status`); answers `ShopifyAccessProblemResponse(store_id, first_report: Boolean)`, `404` for a store
  the monolith does not know. It upserts the status row with `source = dss_report`, `token_status` `rejected` or
  `valid`, the missing scopes, and sets `reauthorization_needed_since` (kept if already set) and the reason. It is
  `first_report = true` when the row had no `reauthorization_needed_since`, or a different reason: that is when the
  notification goes out (below). Idempotent otherwise: eight redeliveries are eight `first_report = false`.
- **`PUT /stores/api-key`** (changed): `UpdateStoreApiKeyRequest` gains `granted_access_scopes: List<String>?` and
  `missing_access_scopes: List<String>?`. When both are present (an install), the handler also upserts the status row
  with `source = install`, `token_status = valid`, the two lists, and clears `reauthorization_needed_since` when
  `missing_access_scopes` is empty, or sets it with the reason `access scope(s) <handles> not granted` when it is not.
  When they are null (the monolith's own re-send of a token, `PUT /stores/api-key` on the DSS), the status row is not
  touched.
- **Re-enqueue after the fix**: when an install or a check clears `reauthorization_needed_since`, the handler enqueues
  `SyncShipmentsWithFulfillments` once per Shopify order of the store that has a shipment with `synced_at is null`
  (a query in `db/sql/job/`). The job no-ops for an order with nothing left, so an over-enqueue costs nothing. Tracking
  updates refused meanwhile are not replayed (`ForwardTrackingUpdateToDss` carries no watermark); the spec appendix
  says so and `000`'s open question 6 covers the orders.
- **Notification**: on `first_report = true` (and on an install that leaves scopes missing), a notification to the
  retailer's users with `ManageStores`, through the existing notification system: the store's name, the reason, and the
  reconnect link (`DssWebhookService.installUrl`). One per distinct reason; a repeat with the same reason sends nothing.
- **The check path** (`040` calls it, `050` schedules it): a workflow `checkStoreShopifyStatus(store)` in `workflow/`
  that calls `DssWebhookService.shopifyStoreStatus`, upserts the row with `source` `manual_check` or `scheduled_check`,
  derives `token_status` (`valid` on `200`, `missing` on `401`, `unknown` on a transport failure or `502`),
  `webhooks_status`, the scope lists, `status_detail`, and sets or clears the reauthorization flag from what it found.
  A `403` (the subscription scan refused for a missing scope, `020`) records `token_status = valid`, no scope lists
  and no webhooks status, and sets the flag with the scope names from the message as the reason. This spec delivers
  the workflow; the callers are `040` and `050`.

### DSS

- **The push**: `MonolithService.postShopifyAccessProblem(request: ShopifyAccessProblemRequest): MonolithResult<ShopifyAccessProblemResponse>`
  (`HttpMonolithService`, `FakeMonolithService`, `OutBoundMonolithPaths.storesShopifyAccessProblems` generated), called
  from one place, a workflow function `reportShopifyAccessProblem(monolith, shop, error, observedDuring)` that takes a
  `ShopifyError` and does nothing unless it is `TokenRejected` or `AccessDenied`. Called by:
  - `ShopifyWebhookHandlers`, after a mirror ends in `ShopifyFailed` with one of the two, through the webhook monolith
    service (one retry) and inside the mirror budget, before the `502` is answered;
  - `MonolithWebhookHandlers.handleSyncShipments` and `handleTrackingUpdate`, before answering the `401` or `403`;
  - the `010` status handler, when the access scopes query answers `TokenRejected` (a dead token found by a check is
    worth the same report, so the monolith's manual check flags the store even though it reads the `401` too).
  A failed push is logged through `logMonolithFailure` and changes the answer to nothing: the monolith's own `401`
  or `403` and the next check carry the same fact.
- **The install**: `persistTokenToMonolith` sends `granted_access_scopes` and `missing_access_scopes` from the
  `ShopifyAccessScopeReport` the callback built; `MonolithWebhookHandlers.handlePutStoreApiKey` forwards the request's
  lists as they came (null from the monolith).


## Behavioral contract

- **Every `TokenRejected` and every `AccessDenied` the DSS sees on a shop the monolith knows ends in a status row with
  `reauthorization_needed_since` set**, within the same request, or on the next check if the push failed.
- **The retailer's users are notified once per distinct reason**, not per failure.
- **An install that grants everything clears the flag** and re-enqueues the store's unsynced shipment syncs; one that
  grants less keeps it with the new reason.
- **The problem endpoint and the changed `PUT /stores/api-key` are idempotent**: the same request twice leaves the row
  as one request did, and sends one notification.
- **The DSS's answers to the monolith do not change** because of the push: a `401` is a `401`, a `403` a `403`, a
  webhook's `502` a `502`, with or without the monolith having taken the report.


## Edge cases

- A report for a subdomain the monolith does not know: `404`, logged at warn on the DSS, nothing else.
- A `TokenRejected` for a token the DSS had already replaced (a reinstall this instance never saw): the report sets the
  flag; the next check finds the token valid and clears it. Acceptable: a check follows every install (`040`) or a day
  (`050`).
- The install with the monolith down: `PUT /stores/api-key` fails, the page says so (as today), the status row is
  stale until the next check. No push is attempted for a store without a token.
- A problem report racing an install (the merchant re-authorized while a redelivery was in flight): the report may set
  the flag after the install cleared it. To keep the newer fact, `ShopifyAccessProblemRequest` carries `observed_at`
  (the DSS's clock, when the failure was seen), and the endpoint ignores a report older than the row's `checked_at`
  when that row came from an install or a check. Spec default; the alternative is to accept the race and let the next
  check settle it.
- Webhook budget: the push is one more monolith call inside the four seconds. It goes after the mirror's own work has
  failed, so it competes with nothing; if the budget is gone, it is skipped and logged (`reason=budget_exhausted`).


## Reuse inventory

- Monolith: `UpdateStoreApiKeyApiPutHandler` and `updateStoreApiKey` (the upsert pattern), `pgmqEnqueue`,
  `SyncShipmentsWithFulfillments`, `notificationsWrite.kt` and `userIdsForNotificationRead.kt`, the RLS policy on
  `stores`.
- DSS: `persistTokenToMonolith`, `logMonolithFailure`, `MonolithWebhookHandlers`, `ShopifyWebhookHandlers`' mirror
  outcome handling, `FakeMonolithService` (a `postShopifyAccessProblemCalls` list and a configurable status).


## Test plan

- Monolith `ShopifyAccessProblemApiPostHandler` (`DssApiE2eTest`): first report creates the row and one notification;
  the same report again is `first_report = false` and no second notification; a different reason notifies again; an
  unknown subdomain is `404`.
- Monolith `UpdateStoreApiKeyApiPutHandler` (`DssApiE2eTest`, `ShopifyServiceApiWriteDbTest`): with lists and nothing
  missing, the flag clears and one `SyncShipmentsWithFulfillments` per order with an unsynced shipment is queued; with
  a missing scope, the flag is set with the reason; with null lists, the row is untouched.
- Monolith `CheckStoreShopifyStatusDbTest`: each DSS answer (`200` complete, `200` with a missing scope, `401`, `502`,
  transport) produces the documented row.
- DSS `HttpMonolithServiceTest` (wire): the new method, request and decoded answer, plus `404` and transport.
- DSS `ReportShopifyAccessProblemTest` (fake-backed): only the two errors are reported, with the right kind, handles
  and label.
- DSS `ShopifyWebhookHandlersTest`, `MonolithWebhookHandlersTest`: a `TokenRejected` and an `AccessDenied` each leave
  one call in `postShopifyAccessProblemCalls` and the answer unchanged; a failed push changes the answer not at all.
- DSS `OAuthHandlersTest`: `putStoreApiKeyCalls.single()` carries the granted and the missing lists.
