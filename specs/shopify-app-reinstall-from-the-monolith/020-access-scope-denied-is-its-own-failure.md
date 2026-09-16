# Spec 020: a missing access scope is its own failure

Depends on: `000`. Independent of `010` in code; ordered after it because `030` needs both.
Repos: DSS only. No contract change: `ApiError` keeps its shape, the status code carries the distinction.
Parallel: may be built alongside `010`.


## What changes

Shopify refuses an operation the app has no scope for with `extensions.code: ACCESS_DENIED` and the message
`Access denied for <field> field. Required access: `<scope>` access scope.` Today that is a `ShopifyError.GraphqlError`
with the code in `codes`, `isRetryable = false`: a webhook is answered `200` and the delivery is gone, a monolith call
is answered `502`, which the monolith retries three times into its dead-letter queue. Neither is right for a failure
that a merchant's click fixes.

- `ShopifyError.AccessDenied(message: String, requiredScopeHandles: List<String>)`: recognized in
  `HttpShopifyGraphqlService.execute` when every top-level error carries `ACCESS_DENIED` (a mix with other codes stays
  a `GraphqlError`, so nothing hides behind the friendlier name). `requiredScopeHandles` is parsed from the messages
  with one regular expression over the documented wording; a message that does not match contributes nothing, and the
  list may be empty. `errorLabel` is `shopify_access_denied`.
- `isRetryable = true`. A redelivery goes differently once the merchant approves the scope, and `030` makes that
  approval the next thing that happens; Shopify's four-hour window then recovers the deliveries made meanwhile. The
  cost, a subscription removed after 24 hours of failures, is repaired by the same reinstall (and by `010`'s
  registration). The KDoc on `isRetryable` says this is the one retryable failure that waits for a human, and why.
- `toDssError`: `AccessDenied` maps to a new `DssError.ShopifyAccessScopeMissing(requiredScopeHandles)`, a `403`, with
  the message `Shopify refused: the app's grant on this shop lacks the access scope(s) <handles>; re-authorize the app`
  (or `lacks an access scope` when the list is empty). `403` was `InvalidSignature`'s alone, and that one never
  answers a monolith-facing route, so on those routes `401` means "no or dead token, reinstall" and `403` means
  "wrong grant, re-authorize". The monolith's `integrationErrorFor` already reads a `403` as `Rejected`: no retry, and
  the shipments stay unsynced for `030` to pick up.
- `ShopifyWebhookHandlers`: nothing to dispatch on; `WebhookMirrorOutcome.ShopifyFailed(AccessDenied).isTransient` is
  now true, so the delivery is a `502`. The delivery report's summary line logs an `AccessDenied` at error level (a
  human must act) with `error=shopify_access_denied` and `required_scopes=<handles>`.
- `WebhookSubscriptionHandlers.handleApiCheck` (or its `010` successor): the access scopes query answering
  `AccessDenied` is still `status: unknown`, unchanged; the scan answering it is a `403` now rather than a `502`.


## Behavioral contract

- Given Shopify answers `200` with `errors: [{message: "Access denied for fulfillmentOrders field. Required access:
  `write_merchant_managed_fulfillment_orders` access scope.", extensions: {code: "ACCESS_DENIED"}}]`, every
  `ShopifyGraphqlService` method answers `Failure(AccessDenied(requiredScopeHandles =
  ["write_merchant_managed_fulfillment_orders"]))`.
- `POST /sync-shipments-with-fulfillments` and `POST /tracking-update` answer `403` with an `ApiError` naming the
  handles when the workflow fails that way; nothing is retried on the DSS side.
- A verified `orders/create` delivery whose order load fails that way is answered `502` and logged at error level with
  `error=shopify_access_denied`.
- The token is not evicted: the token is valid, the grant is short.


## Edge cases

- Two errors, one `ACCESS_DENIED` and one `THROTTLED`: a `GraphqlError` with both codes, retryable, as today.
- An `ACCESS_DENIED` whose message has no `Required access:` part: `AccessDenied` with an empty list; the `403` message
  names no scope.
- A required scope the install does not ask for at all (Shopify tightened a field): the handle is reported as is, and
  the monolith's flag (`030`) shows it; the reinstall does not fix it, which is what `000`'s open question 6 is about.
- `ACCESS_DENIED` on the access scopes query itself (`currentAppInstallation` needs no scope; not expected): `unknown`
  as today.


## Reuse inventory

- `HttpShopifyGraphqlService.execute` and `GraphQLClientError.code()`; `ShopifyError.errorLabel`; `toDssError`;
  `WebhookDeliveryReport` and its level rule; `DssError.toHttpStatus`.
- `FakeShopifyGraphqlServer` answers a registered response per operation name: an `ACCESS_DENIED` envelope fixture
  beside the existing ones in `testutil/fixture/`.


## Test plan

- `HttpShopifyGraphqlServiceTest` (wire): an `ACCESS_DENIED` envelope on a read (`GetOrderForDss`) and on a mutation
  (`FulfillmentCreateWithLineItems`) is `AccessDenied` with the parsed handle; a mixed envelope is a `GraphqlError`;
  a message without the handle gives an empty list.
- `ShopifyErrorTest` (pure): `AccessDenied.isRetryable`, `errorLabel`.
- `ToDssErrorTest` (pure): `AccessDenied` → `ShopifyAccessScopeMissing` → `403`, message with and without handles.
- `MonolithWebhookHandlersTest` (request → response): `sync-shipments` with the fake answering `AccessDenied` is a
  `403` `ApiError`, and no fulfillment was created.
- `ShopifyWebhookHandlersTest`: `orders/create` with `orderForDssResult = Failure(AccessDenied(…))` is a `502`, nothing
  posted to the monolith, and (under `capturingLogs`) one `Webhook done` line at error level with
  `error=shopify_access_denied`.
