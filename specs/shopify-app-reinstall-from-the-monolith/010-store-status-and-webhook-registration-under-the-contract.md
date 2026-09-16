# Spec 010: the store status and the webhook registration under the contract

Depends on: `000` (the design).
Repos: monolith first (it serves the contract), then the DSS; the checked-in spec copy in between.
Parallel: none; `020` and `030` build on the DTOs this spec introduces.


## What changes

The two DSS endpoints the monolith has to call exist and work; what they lack is a place in the contract, a client on
the monolith, and the monolith's way of naming a store. This spec gives them all three and nothing else: the answers
keep their shape, the rows keep their statuses, the workflows are untouched.

### Monolith

- `domain/api/dss/`: new `@Serializable` DTOs with an `example` each, snake_case keys:
  - `ShopifyStoreStatusResponse(shopify_subdomain, shop_domain, has_token_mapped_for_shop, access_scopes:
    ShopifyAccessScopesStatus, webhooks: List<ShopifyWebhookTopicRow>, obsolete_webhooks: List<ObsoleteShopifyWebhookRow>)`.
  - `ShopifyAccessScopesStatus(status: "complete" | "missing" | "unknown", granted: List<String>, missing: List<String>)`.
  - `ShopifyWebhookTopicRow(topic, status, id?, uri?, previous_uri?, include_fields?, expected_include_fields?, filter?,
    format?, stale: List<StaleShopifyWebhookRow>)`; `StaleShopifyWebhookRow(id, uri)`;
    `ObsoleteShopifyWebhookRow(topic, id, status)`. The `status` vocabularies are the ones `WebhookSubscriptionHandlers`
    answers today (`active`, `mismatched`, `missing`, `added`, `updated`, `repointed`, `not_applied`, `failed`;
    `obsolete`, `deleted`, `delete_failed`), spelled out in the field description so the monolith's translation of a
    status to a badge has a list to be complete against.
  - `RegisterShopifyWebhooksRequest(shopify_subdomain)`.
  - `RegisterShopifyWebhooksResponse(shopify_subdomain, shop_domain, summary: ShopifyWebhookRegistrationSummary,
    webhooks, obsolete_webhooks)`; `ShopifyWebhookRegistrationSummary(active, added, updated, repointed, failed, stale,
    deleted, obsolete)`.
- `routing/dssApiRouter.kt`: three `webhook` entries beside the two existing ones:
  - `shopify-store-status`, `GET`, `queries += shopify_subdomain`, returning `ShopifyStoreStatusResponse`; error
    answers `400`, `401` ("no Admin token for the shop"), `502`. The description says it is read-only and costs one
    monolith lookup and two Shopify queries.
  - `register-shopify-webhooks`, `POST`, receiving `RegisterShopifyWebhooksRequest`, returning
    `RegisterShopifyWebhooksResponse`; the description says it changes the shop's subscriptions, never the token, and
    that running it twice changes nothing the first run left right.
  - `install`, `GET`, `queries += shop`, no body, a `302` to Shopify's authorize URL. Documented so the monolith may
    build `{DSS_BASE_URL}/install?shop=<subdomain>.myshopify.com` itself; the description says who has to open it
    (someone who can install apps on that shop) and what they see (Shopify's grant screen, "update" for an installed
    app whose scope list changed).
- `lib/dss/DssWebhookService`: two methods, `shopifyStoreStatus(shopifySubdomain): IntegrationResult<ShopifyStoreStatusResponse>`
  and `registerShopifyWebhooks(shopifySubdomain): IntegrationResult<RegisterShopifyWebhooksResponse>`, plus
  `installUrl(shopifySubdomain): Uri`. `FakeDssWebhookService` records both calls and answers what a test configured.
  The service is constructed once in `main.kt` and handed to the HTTP server as well (today only the PGMQ workers get
  one); how it reaches a handler follows the pattern of the other injected services (a constructor parameter of the
  handler, through the router), which `040` uses.
- `DssWebhookService` maps the DSS's statuses: `401` is `IntegrationError.Rejected` today, which the two new callers
  read as "no token" for the status call; `040` decides what a page shows for each.

### DSS

- `Paths`: `apiCheck` and `apiWebhooksRegister` go; `storesShopifyStatus = "/stores/shopify-status"` and
  `storesShopifyWebhooksRegister = "/stores/shopify-webhooks/register"` come, in the "DSS internal REST" group.
  `webhookSubscriptionRoutes` mounts them under the same `authenticate(MONOLITH_WEBHOOK_AUTH)` block as before.
- `WebhookSubscriptionHandlers`: the private DTOs go; the handlers answer the generated
  `dropnext.dss.contract.ShopifyStoreStatusResponse` and `RegisterShopifyWebhooksResponse`. The status handler reads
  `shopify_subdomain` from the query (`call.request.queryParameters.getOrFail("shopify_subdomain")`, then
  `shopDomainOrRespond`); the registration handler `receive`s `RegisterShopifyWebhooksRequest`, validated by a new
  `validateRegisterShopifyWebhooksRequest` in `domain/` (a blank subdomain is a `400`) registered in
  `installRequestValidation`, like `validateUpdateStoreApiKeyRequest`. The row-building functions (`topicRows`,
  `obsoleteRows`, `toApiCheckAccessScopes`) move to a `mapper/` file, `toShopifyStoreStatusRows.kt`, since they now map
  domain reports onto contract DTOs, which is what `mapper/` is for.
- `warmUpBeforeTakingTraffic` and `HttpWarmUpLoopbackService`: the inbound warm-up sends `GET /stores/shopify-status`
  for `WARM_UP_PLACEHOLDER_SHOP` instead of `GET /api/check`.
- `DiagnosticsHandlers.handleIndex`, `README.md`, `CLAUDE.md`: the two routes move from "Webhook subscriptions" into
  "DSS internal REST" with their new paths.


## Behavioral contract

- `GET /stores/shopify-status?shopify_subdomain=acme` answers exactly what `GET /api/check?shop=acme` answered, with
  `shopify_subdomain: "acme"` and `shop_domain: "acme.myshopify.com"` in place of `shop`, and `has_token_mapped_for_shop`
  at the top level in place of `checks.has_token_mapped_for_shop`.
- `POST /stores/shopify-webhooks/register` with `{"shopify_subdomain": "acme"}` answers exactly what
  `POST /api/webhooks/register?shop=acme` answered, with the same two naming changes.
- Both are `401` without the bearer, `400` for a subdomain `ShopDomain.parse` refuses, `401` `ApiError` for a shop
  without a token, `502` `ApiError` when the token lookup or the subscription scan did not get an answer.
- The monolith's `shopifyStoreStatus("acme")` sends `GET {DSS_BASE_URL}/stores/shopify-status?shopify_subdomain=acme`
  with the bearer and the trace id, and decodes a `200` into the DTO; `registerShopifyWebhooks("acme")` sends the
  POST with the JSON body. A non-`200` is an `IntegrationError` through `integrationErrorFor`.
- `installUrl("acme")` is `{DSS_BASE_URL}/install?shop=acme.myshopify.com`, URL-encoded.


## Edge cases

- A subdomain with the suffix (`acme.myshopify.com`) in `shopify_subdomain`: `ShopDomain.parse` accepts it; the answer
  still names `shopify_subdomain: "acme"`. The monolith never sends it that way.
- The registration for a shop whose scan fails: `502`, nothing registered, nothing deleted (unchanged).
- The `install` entry has no DTOs: an OpenAPI webhook entry may describe a `302` with no body. If http4k's `webhook`
  DSL cannot render a bodiless `GET`, document it in the spec's `info.description` instead and keep the path as a
  constant in `DssWebhookService`; say so in the implementation appendix.


## Reuse inventory

- DSS: everything in `WebhookSubscriptionHandlers` and the workflows it calls; `shopDomainOrRespond`,
  `shopifyServiceOrRespond`; `installRequestValidation` + `validateUpdateStoreApiKeyRequest` as the pattern.
- Monolith: `TrackingUpdateRequest` as the DTO pattern; the `webhook("tracking-update")` entry; `DssWebhookService.jsonPost`
  (a `jsonGet` sibling for the status call); `FakeDssWebhookService`.


## Test plan

- DSS `WebhookSubscriptionHandlersTest` (request → response through `withDssApp`): every existing case re-pointed at
  the new paths and the new parameter name, decoding the generated DTOs; a missing `shopify_subdomain` is a `400`; the
  old paths are `404`.
- DSS `MonolithWebhookHandlersTest` gains nothing (no new monolith-facing handler here).
- DSS `WarmUpBeforeTakingTrafficTest` / `HttpWarmUpLoopbackServiceTest`: the inbound step hits the new path.
- Monolith `DssWebhookServiceTest`: both new methods against a stub `HttpHandler`: method, path, query or body, bearer,
  trace id, the decoded answer, and a `401`, `502` and unreadable body each as the right `IntegrationError`.
- Monolith `DssOpenApiHandlerE2eTest`: the rendered spec carries the three new `webhooks` entries and the new schemas.
