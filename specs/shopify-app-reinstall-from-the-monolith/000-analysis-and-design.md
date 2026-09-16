# Reinstalling the Shopify app from the monolith: analysis and design

Status: Phase 1 draft, for human review. Checked against Shopify's documentation on 2026-09-16.
Author: cies (with Claude)
Repos: both. The monolith serves the contract, so its side lands first in every spec that touches it
(`../CLAUDE.md`, "Cross-repo couplings"); the checked-in copy `src/resources/monolith-dss-openapi.json` is refreshed in
the same work session.


## The problem

A shop's installation of the DropNext app breaks in three ways, and today every one of them is discovered by a human
reading logs:

1. **The scopes changed on our side.** `ShopifyAccessScope` gains an entry (a deploy). Every shop installed before it
   keeps its old grant, and everything that needs the new scope fails with Shopify's `ACCESS_DENIED` until the
   merchant approves the new list. Nothing tells the merchant, and nothing tells the monolith.
2. **The merchant uninstalled the app** (or the token was revoked). Shopify answers `401`, the DSS evicts the token
   and answers the monolith `401` (`DssError.ShopifyAdminTokenRejected`); the monolith's job logs an error and stops
   retrying (`IntegrationError.Rejected`). The store keeps its "Connected" badge in the retailer portal.
3. **The webhook subscriptions rotted**: Shopify removed one after 24 hours of failed deliveries, an earlier version
   registered a topic the service no longer handles, or a subscription points at an old callback URL. Orders stop
   arriving and nobody sees why.

The DSS can already answer all three questions for one shop, and repair the third, but only for a caller with the
bearer token and a terminal: `GET /api/check?shop=` and `POST /api/webhooks/register?shop=` are outside the contract,
so the monolith cannot call them, and no portal page shows what they answer. The first two are repaired only by the
merchant opening `{DSS_BASE_URL}/install?shop=…`, a link nobody hands them.


## What Shopify does

- **Adding scopes to an app that runs its own OAuth** (ours: `HttpShopifyOAuthService.authorizeUrl`): "send the
  merchant through the authorization URL again" with "the updated scope list. In every case, merchants are prompted
  to approve scopes you add." The merchant sees Shopify's grant screen for an installed app, which asks them to update
  the app's permissions; approving redirects to our OAuth callback like a first install, with a token carrying the
  new grant. There is no separate "upgrade" of a custom app on the merchant's side: the click-wrap is that grant
  screen. The Partner Dashboard configuration should list the same scopes (`README.md`, "Shopify Partner app"); the
  authorize URL is what decides the grant for our flow.
- **A missing scope** is reported by the Admin Graphql API with HTTP `200`, a top-level error
  `Access denied for <field> field. Required access: `<scope>` access scope.` and `extensions.code: ACCESS_DENIED`
  (the message wording has been stable since 2020-07). The DSS folds this into `ShopifyError.GraphqlError(codes =
  [ACCESS_DENIED])` today, which `isRetryable` treats as permanent.
- **The granted scopes** are readable at any time through `currentAppInstallation.accessScopes`, which the DSS
  already queries (`CurrentAppInstallationAccessScopes.graphql`) for `/api/check`.
- **`app/scopes_update`** (API version 2024-10 and later) fires "when the granted access scopes for the installed app
  on a shop have been modified", with `previous`, `current`, `updated_at` and `shop_id`. For our own OAuth flow it is
  redundant: every grant change the merchant approves ends in our callback. Not subscribed in this design (open
  question 5).
- **An uninstall** is only observable as a `401` on the next call, until `app/uninstalled` is handled
  (`specs/shopify-public-app-requirements/020`, not built). This design does not wait for it: the `401` is pushed to
  the monolith the moment it is seen.
- **Shopify's webhook retry** for a non-`2xx`: eight redeliveries within four hours; after 24 hours of failures the
  subscription is removed.

Sources: [Manage access scopes](https://shopify.dev/docs/apps/build/authentication-authorization/app-installation/manage-access-scopes),
[`app/scopes_update` changelog](https://shopify.dev/changelog/new-webhook-topic-app-scopes_update),
[`currentAppInstallation`](https://shopify.dev/docs/api/admin-graphql/latest/queries/currentAppInstallation),
[ACCESS_DENIED error messages](https://community.shopify.dev/t/new-error-messages-for-graphql-operations-without-necessary-access-scopes/31042),
[troubleshoot webhooks](https://shopify.dev/docs/apps/build/webhooks/troubleshooting-webhooks).


## What exists (reuse inventory)

DSS:
- `WebhookSubscriptionHandlers` (`handler/`): `handleApiCheck` (token resolves, `access_scopes` complete/missing/unknown,
  one row per handled topic, obsolete rows) and `handleRegisterWebhooks` (the install's registration without the
  merchant, with a `summary`). Their response DTOs are private to the file. Both take `shop` as a query parameter.
- `scanShopifyWebhooks`, `reregisterShopifyWebhooks`, `registerShopifyWebhooks` (`workflow/`); `ShopifyAccessScopeReport`,
  `WebhookRegistrationReport` (`domain/`).
- `installShop` + `persistTokenToMonolith` (`workflow/installShop.kt`): the OAuth callback's tail, which already calls
  `PUT /stores/api-key` on the monolith with the token, and holds the granted scopes in a `ShopifyAccessScopeReport`
  it renders on the page and then drops.
- `ShopifyError` + `toDssError` (`lib/shopify/graphql/`, `handler/`): the one triage and the one mapping to HTTP.
- `ShopTokenStore.forget` + `HttpShopifyGraphqlService.onTokenRejected`: a `401` already evicts the token.
- `MonolithService` / `HttpMonolithService` / `FakeMonolithService`: every outbound monolith call; a new one needs all
  three plus a wire test (`TestSuiteArchitectureTest`).
- `warmUpBeforeTakingTraffic` sends itself `GET /api/check` (moves with the route).

Monolith:
- `routing/dssApiRouter.kt`: the http4k `contract {}` that renders `/openapi.json`. Monolith endpoints are `routes`;
  DSS-inbound endpoints are `webhook("name") { … bindWebhook POST }` entries, which land in the spec's `webhooks:`
  block (OpenAPI 3.1) with their DTOs in `components.schemas`.
- `domain/api/dss/*` (`TrackingUpdateRequest`, …): the DTOs of the DSS-inbound calls, each with an `example`.
- `lib/dss/DssWebhookService`: the outbound client to the DSS (`sendTrackingUpdate`, `syncShipmentsWithFulfillments`),
  answering `IntegrationResult`; `FakeDssWebhookService` in `test/`. Built only for the PGMQ workers today
  (`main.kt`); the HTTP server has no instance.
- `handler/api/dss/apiHandlers.kt`: `StoreApiGetHandler`, `UpdateStoreApiKeyApiPutHandler`; `db/sql/shopifyServiceApi{Read,Write}.kt`.
- `stores` (`store_id`, `retailer_id`, `name`, `shopify_subdomain`, `shopify_shop_id`, `encoded_api_key`, with the
  check that id and key are set together): "connected" today means `encoded_api_key is not null`
  (`listStoresPage`'s badge).
- Retailer portal: `Paths.retailerPortal.settings.{listStores,updateStore,createStore}`, `storeHandlers.kt`,
  `RetailerUserPermission.{ViewStores,ManageStores}`. Admin portal: `showRetailer`, `AdminUserPermission`, the
  `development` section holding the DSS API docs.
- Notifications (`db/sql/notificationsWrite.kt`, `presentation/notification/`), `pg_cron` schedules that enqueue PGMQ
  jobs (`supabase/migrations/*_cron.sql`), `PgmqHandler` jobs with `retryUnlessRefused`.


## Design

Four pieces, each a spec:

1. **The two DSS endpoints come under the contract** (`010`), renamed to the contract's conventions and keyed by
   `shopify_subdomain` like every other DSS-inbound call. The monolith gets a client method for each.
2. **A missing scope becomes its own failure** (`020`): `ShopifyError.AccessDenied`, answered to the monolith as `403`
   so a job stops retrying, and to Shopify as `502` so the four-hour redelivery window covers a prompt re-authorization.
3. **The monolith learns of a broken installation** (`030`): the DSS pushes a token rejection or a missing scope to a
   new monolith endpoint the moment it sees one, and the install reports the grant it got through the existing
   `PUT /stores/api-key`. The monolith keeps one status row per store, notifies the retailer's users once, and
   re-enqueues the shipment syncs that were refused once the store is re-authorized.
4. **Portal pages and actions** (`040`): the retailer's store page shows the connection status with three actions
   (check now, re-register webhooks, reconnect); the admin gets a page over every store, with a check of all of them.
   The reconnect is a link to `{DSS_BASE_URL}/install?shop=…`, which is all a reinstall or a scope update takes.
5. **A scheduled check** (`050`): the status of every connected store refreshed daily, so the admin page is never
   older than a day and a rotted subscription is found before the retailer notices.

"Automatic reinstall" is, by Shopify's design, not possible: only a merchant with the right to install apps can
approve a grant. What is automated is everything up to that click: recognizing the failure, telling the monolith,
flagging the store, notifying the retailer's users with the link, and picking the refused work back up after the
grant. That is what `020` and `030` deliver.


## What comes under the OpenAPI contract, and how

The monolith serves the spec; the DSS generates from the checked-in copy. Every DTO below is a `@Serializable` class in
the monolith's `domain/api/dss/` with an `example`, and appears in the DSS as a generated `dropnext.dss.contract.*`
class. Names are final unless the human developer changes them; nothing is kept for backward compatibility.

### DSS-inbound (a `webhooks:` entry each, in `dssApiRouter.kt`)

| Entry | Method and path on the DSS | Request | Response | Replaces | Spec |
|---|---|---|---|---|---|
| `shopify-store-status` | `GET /stores/shopify-status?shopify_subdomain=` | query `shopify_subdomain` | `ShopifyStoreStatusResponse` | `GET /api/check?shop=` | `010` |
| `register-shopify-webhooks` | `POST /stores/shopify-webhooks/register` | `RegisterShopifyWebhooksRequest` | `RegisterShopifyWebhooksResponse` | `POST /api/webhooks/register?shop=` | `010` |
| `install` | `GET /install?shop=` | query `shop` (`<subdomain>.myshopify.com`) | `302` to Shopify | nothing: documented so the monolith may build the link | `010` |
| `stores-api-key` | `PUT /stores/api-key` | `UpdateStoreApiKeyRequest` | `UpdateStoreApiKeyResponse` | itself, undocumented as a DSS route until now | `030` |

Error answers on all of them: `401` (bearer), `400` (`ApiError`, a subdomain that is not one), `401`
`ApiError` for a shop without a token, `403` `ApiError` for a grant that lacks a scope (`020`), `502` `ApiError` when
Shopify or the monolith did not answer.

### Monolith endpoints (a `routes` entry each)

| Method and path on the monolith | Request | Response | Spec |
|---|---|---|---|
| `POST /stores/shopify-access-problems` | `ShopifyAccessProblemRequest` | `ShopifyAccessProblemResponse` | `030` |
| `PUT /stores/api-key` (changed) | `UpdateStoreApiKeyRequest` gains `granted_access_scopes` and `missing_access_scopes`, both nullable lists | unchanged | `030` |

### Shared schemas

- `ShopifyStoreStatusResponse`, `ShopifyAccessScopesStatus`, `ShopifyWebhookTopicRow`, `StaleShopifyWebhookRow`,
  `ObsoleteShopifyWebhookRow` (`010`): the JSON `handleApiCheck` answers today, unchanged in shape apart from the key
  under which the shop is named.
- `RegisterShopifyWebhooksRequest`, `RegisterShopifyWebhooksResponse`, `ShopifyWebhookRegistrationSummary` (`010`).
- `ShopifyAccessProblemRequest`, `ShopifyAccessProblemResponse`, and the enum `ShopifyAccessProblemKind`
  (`token_rejected`, `access_scope_missing`) (`030`).
- `ApiError` stays as it is: the status code carries the distinction the monolith acts on (`401` reinstall, `403`
  re-authorize, `502` retry), and the message carries the scope names for a human (open question 2).

### Not under the contract

- `/health`, `/`, `/api`, `/api/redirect-url`: diagnostics for a human, not the monolith.
- `/oauth/callback` and `/webhooks/shopify`: Shopify's, not the monolith's.

### Refreshing the checked-in copy

The monolith renders the spec at `GET {MONOLITH_BASE_URL}/api/shopify-service/v1/openapi.json` (bearer
`DSS_TO_MONOLITH_API_KEY`) and, for an admin, at the admin portal's development section. The human developer saves it
over `src/resources/monolith-dss-openapi.json`; `./gradlew openApiGenerate generateOutBoundMonolithPaths` then
regenerates `dropnext.dss.contract` and `OutBoundMonolithPaths`. Claude does neither call: the monolith is remote or a
human-run local instance ("Operational boundary").


## Edge cases the specs cover

- A store the monolith knows but that was never connected: the status is `not_connected`, the only action is the
  install link. The DSS answers `401` for it (no token), which the monolith reads as that, not as an outage.
- A DSS that is down: the status page says so and keeps the last known row; the install link is shown anyway (a DSS
  that is down cannot install either, but the link is not wrong).
- The install callback with the monolith down: today the page says the token is cached in memory only; with `030`
  the grant is lost with it, and the next scheduled or manual check restores the row.
- A token rejected while a newer one exists (a reinstall the DSS instance never saw): `ShopTokenStore.forget` handles
  the eviction; the problem push carries the token's rejection as observed, and the monolith clears the flag on the
  next check that finds the token valid.
- Shopify redelivering a webhook eight times while the scope is missing: eight pushes to the monolith, one notification.
- A merchant who grants less than asked on the reinstall: the callback's `missing_access_scopes` is not empty, the
  flag stays set with the new reason, no notification is repeated for the same missing set.
- A subdomain that is not one (`shopify_subdomain=evil.com/`): `400` from the DSS, never a redirect anywhere.


## Open questions for the human developer

1. **Naming.** `GET /stores/shopify-status` and `POST /stores/shopify-webhooks/register` follow `/stores/api-key`;
   the flat verb style of `/sync-shipments-with-fulfillments` would give `GET /shopify-store-status` and
   `POST /register-shopify-webhooks`. Either is fine; the specs use the first.
2. **A machine-readable error code.** `ApiError` could gain `code` (`shopify_admin_token_missing`,
   `shopify_admin_token_rejected`, `shopify_access_scope_missing`, …) so the monolith reads a reason rather than a
   status. The specs do without: the three statuses are enough for what the monolith does, and the push of `030`
   carries the detail anyway. Say so if you want the code.
3. **Who may reconnect.** The specs give the retailer's `ManageStores` the check and the reconnect link, and the
   re-registration of webhooks too. If re-registration is an admin-only repair, drop it from `040`'s retailer page.
4. **Notification channel.** `030` notifies the retailer's users through the existing notification system (in-app,
   and email if the preference says so). Should admins be notified as well?
5. **`app/scopes_update`.** Not subscribed: our own OAuth callback sees every grant the merchant approves. It would
   only add coverage for a grant changed outside our flow. Subscribe anyway?
6. **Orders missed while a scope was missing.** With `020`, Shopify redelivers `orders/create` for four hours. An
   order whose deliveries ran out is not recoverable through the DSS today (no "import this order" endpoint). Worth a
   follow-up spec, or accepted?
7. **The daily check (`050`)** costs two Shopify queries and one DSS call per connected store per day. Daily, or on
   demand only?
