# Spec: expiring offline access tokens

Status: draft
Author: cies (with Claude)
Date: 2026-09-12
Depends on: the answer to "Precondition" below, given in `000`: custom distribution, so this is not built. Independent of
the other spec folders.
Repos: monolith first (it stores the tokens and serves the contract), then the DSS in the same session
(`../CLAUDE.md`, "Cross-repo couplings").


## Precondition: is the app public?

Shopify requires expiring offline access tokens for public apps: for public apps created since 2026-04-01, and for
every public app from 2027-01-01, after which a public app that still sends a non-expiring token gets authentication
errors. Custom apps and merchant-created apps are not affected.

- Public (App Store, or unlisted public distribution): this spec has to land, and its migration run, before
  2027-01-01. Every Shopify call the DSS makes stops working on that date otherwise.
- Custom distribution: nothing here is required. The spec stays as a record of what a switch to public distribution
  would take.
- A switch is not a setting: Shopify does not change an app's distribution method, so going public is a new app (`000`).
  Created after 2026-04-01, that app uses expiring tokens from its first install, and every shop installs it anew: the
  exchange of non-expiring tokens ("Migrating" below, and rollout step 3) applies only to an app that already is public.
  The refresh, and its single owner, remain.


## Problem

The token model assumes a token lives forever:

- `HttpShopifyOAuthService.exchangeCode` asks for a non-expiring offline token (no `expiring` parameter) and reads
  `access_token` alone.
- `installShop` caches it in the `InMemoryShopTokenStore` of the task that handled the callback and persists it to the
  monolith (`PUT /stores/api-key`, stored encrypted in `stores.encoded_api_key`).
- Every other task finds it through the store's fallback, `resolveShopTokenFromMonolith` (`GET /stores`), and keeps
  it until Shopify answers `401`, which evicts it (`onTokenRejected`).

Nothing knows when a token expires, nothing holds a refresh token, and nothing can refresh one.


## What Shopify does

From Shopify's documentation of offline access tokens (see "Sources"; confirm each point against the docs when this
spec is picked up, the feature is recent):

- **Asking for one.** The token request of the authorization code grant, and of token exchange, takes `expiring: 1`.
- **The answer.** `access_token`, `expires_in` (`3600`), `refresh_token`, `refresh_token_expires_in` (`7776000`, 90
  days) and `scope`.
- **Refreshing.** `POST https://{shop}.myshopify.com/admin/oauth/access_token` with `client_id`, `client_secret`,
  `grant_type=refresh_token` and `refresh_token`. Every refresh answers a new access token *and* a new refresh token.
- **Retiring.** Obtaining a new expiring offline token retires the older ones for the same app and store: there is one
  live refreshable token per app per store.
- **Migrating.** An existing non-expiring token is exchanged, without the merchant: token exchange with
  `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token` (the non-expiring token),
  `subject_token_type` and `requested_token_type` both `urn:shopify:params:oauth:token-type:offline-access-token`, and
  `expiring=1`. The answer is an expiring token with its refresh token, and the non-expiring token is revoked.

The retiring rule decides the design. The DSS runs as one task, but two side by side during every deploy, and each
caches tokens on its own. Two refreshes of one shop's token that overlap retire each other's result, so the refresh
needs a single owner that serialises it.


## What changes

### The monolith refreshes

The monolith already owns the token row, the encryption key, a database to lock in and a job queue. A refresh there
is a row update under `select … for update` on the store, so two refreshes cannot overlap, and every DSS task reads the
result through `GET /stores`.

- It needs the Shopify app's client id and secret, which only the DSS holds today: a new entry in the monolith's
  secret (`dnc edit-secrets`). Open question 1 weighs this against keeping the secret in one service.
- Schema: `stores.encoded_refresh_token`, `stores.api_key_expires_at`, `stores.refresh_token_expires_at`, all nullable
  (a non-expiring token has none of them).
- `refreshShopifyAccessToken(storeId)`: under the row lock, if the stored token still has more than the margin left
  (another caller refreshed meanwhile), answer it; otherwise refresh at Shopify and store the new pair.
- A scheduled job (pg_cron plus pgmq, like the existing jobs) refreshes every token that expires within the margin, so a
  request rarely waits for a refresh.
- `GET /stores` answers the token and `api_key_expires_at`; it refreshes inline first when the stored token is already
  inside the margin (open question 3).
- A one-off migration job exchanges every non-expiring token (see "Rollout").

### Contract

- `UpdateStoreApiKeyRequest` gains `refresh_token`, `expires_in` and `refresh_token_expires_in`, not required and
  nullable, so a DSS that predates the change and a non-expiring token both still decode.
- `StoreResponse` gains `api_key_expires_at`, nullable.
- Both sides of `src/resources/monolith-dss-openapi.json` change in one session.

### DSS

- `exchangeCode` sends `expiring: 1` and decodes `expires_in`, `refresh_token` and `refresh_token_expires_in`.
- A new secret type `ShopifyRefreshToken` in `domain/Secrets.kt`. The DSS does not keep it: it passes through once, from
  the code exchange to `PUT /stores/api-key`.
- `ShopTokenStore` caches the expiry beside the token. `resolve` treats a token inside the margin as a miss and asks the
  monolith, which answers a refreshed one.
- `onTokenRejected` stays: a `401` evicts the token, and the next request asks the monolith again.
- `/api/check` reports the expiry of the token it resolved (not the token).


## Behavioral contract

- **Invariant**: at most one refresh of a store's token is in flight, anywhere.
- **Postcondition of a refresh**: the stored access token and refresh token are the pair Shopify answered last; no
  caller is handed a token that the refresh retired.
- **Precondition of a DSS call**: the cached token has more than the margin left, or it came from the monolith within
  this request.
- **Unchanged**: a shop without a token is `ShopLookup.Missing`; a monolith that does not answer is
  `ShopLookup.Unavailable`, a `502` the caller retries.


## Edge cases

- **A token expires while a request is on its way**: the margin (minutes) is far larger than any request's time budget
  (seconds), so it takes a clock or a job that stopped. The `401` evicts the token; the webhook is then answered as
  a rejected token, which is not retried. Open question 4 asks whether a `401` on a token the DSS believed valid should
  be retried once through the monolith.
- **The refresh token expired** (90 days without a refresh, because the job did not run): Shopify refuses the refresh;
  the store needs a reinstall. The monolith clears the token and logs at `error`; the DSS sees `ShopLookup.Missing`.
- **Shopify refuses a refresh because the app was uninstalled**: the same as the uninstall in `020`.
- **Two DSS tasks during a deploy**: both read from the monolith; neither refreshes.
- **A reinstall during a refresh**: the OAuth callback's `PUT /stores/api-key` takes the same row lock, so the later of
  the two writes wins, and it is the newer token.
- **The migration exchange of a token that a DSS task holds**: the old token is revoked; that task's next call is a
  `401`, which evicts it and fetches the expiring one.


## Rollout

1. Monolith: schema, refresh, job, contract, `GET /stores` inline refresh. Deploy.
2. DSS: the contract copy, `expiring: 1` on install, expiry-aware cache. Deploy.
3. Migration: a human runs the one-off job that exchanges the non-expiring tokens (idempotent by skipping stores that
   already have a refresh token), and checks that none are left, well before 2027-01-01.


## Reuse inventory

- `HttpShopifyOAuthService.exchangeCode` and its `OAuthAccessTokenResponse`; `FakeShopifyGraphqlServer` already serves
  the token endpoint (`oauthAccessTokenResponse`).
- `ShopTokenStore` / `InMemoryShopTokenStore` / `ShopLookup`, and `resolveShopTokenFromMonolith`.
- `installShop` → `persistTokenToMonolith`.
- Monolith: `db/sql/shopifyServiceApiWrite.kt` (`encoded_api_key`), `db/sql/shopifyServiceApiRead.kt`, the store token
  encryption key wired into `UpdateStoreApiKeyApiPutHandler`, and the job pattern of `SyncShipmentsWithFulfillments`.


## Test plan

- **Monolith** (`*DbTest`): the refresh under the lock, two concurrent refreshes end with one Shopify call; the job picks
  exactly the tokens inside the margin; the migration skips stores with a refresh token. A fake Shopify token endpoint.
- **DSS, wire** (`HttpShopifyOAuthServiceTest`): `exchangeCode` sends `expiring` and decodes the three new fields;
  (`HttpMonolithServiceTest`) the new contract fields both ways.
- **DSS, fake-backed** (`InMemoryShopTokenStoreTest`): a token inside the margin is a miss; a fresh one is not.
- **DSS, request → response** (`OAuthHandlersTest`): the callback persists the refresh token and the expiries; the
  refresh token appears in no log line and no page.


## Open questions for the human developer

1. The monolith refreshes (the Shopify client secret moves into the monolith's secret too), or the DSS refreshes under a
   lease the monolith hands out (the secret stays in one service, the lease adds a round trip and a failure mode)?
2. The margin and the job's cadence: refresh when less than fifteen minutes are left, every five minutes?
3. `GET /stores` refreshes inline when the token is inside the margin, or it answers the old token and leaves the
   refresh to the job?
4. A `401` on a token the DSS believed valid: retried once with a token fetched from the monolith, or answered as a
   rejected token as today?


## Sources

- [Shopify: about offline access tokens](https://shopify.dev/docs/apps/build/authentication-authorization/access-tokens/offline-access-tokens)
- [Shopify changelog: expiring offline access tokens required for all public apps as of January 1, 2027](https://shopify.dev/changelog/expiring-offline-access-tokens-required-for-all-public-apps-as-of-january-1-2027)
- [Shopify changelog: expiring offline access tokens required for new public apps as of April 1, 2026](https://shopify.dev/changelog/expiring-offline-access-tokens-required-for-public-apps-april-1-2026)
- [Shopify developer community: migrating from non-expiring to expiring access tokens](https://community.shopify.dev/t/migrating-from-non-expiring-to-expiring-access-tokens/34525)
- [Shopify: select a distribution method](https://shopify.dev/docs/apps/launch/distribution/select-distribution-method)
