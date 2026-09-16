---
paths:
  - "src/resources/*.graphql"
  - "src/graphql-schema/**"
  - "src/dropnext/dss/lib/shopify/**/*.kt"
  - "src/dropnext/dss/workflow/**/*.kt"
  - "src/dropnext/dss/mapper/**/*.kt"
  - "src/dropnext/dss/domain/fulfillment/**/*.kt"
---
# Rules for the Shopify Admin Graphql client

Referenced from `CLAUDE.md` ("Graphql queries").

## Files and codegen
- One operation per file in `src/resources/`, the file named after the operation it declares
  (`GetOrderForDss.graphql` holds `query GetOrderForDss`). `graphqlGenerateClient` turns each into
  `dropnext.graphql.generated.<Operation>` with a nested `Result`, a per-operation package of response types
  (`dropnext.graphql.generated.getorderfordss.Order`), enums in `…generated.enums` and inputs in `…generated.inputs`.
- Response types are named per operation by the schema, so the same shape can appear twice in one operation
  (`MoneyBag`, `MoneyBag2`) and once per operation across files: import with an alias where two operations meet
  (`Fulfillment as CreatedFulfillment`).
- Generated code lives under `build/`; never edit it. Ask only for the fields you use — every field in a query is a
  field a mapper must handle and a fixture must supply.
- The committed `src/graphql-schema/schema.graphql` is what the client compiles against; the IDE's Graphql plugin
  reads it too.

## Bumping the Shopify API version
These must agree, and the compiler only checks the first:
- `endpoint = "https://shopify.dev/admin-graphql-direct-proxy/<version>"` on `tasks.graphqlIntrospectSchema` in
  `build.gradle.kts` (introspection source; codegen itself reads the committed schema, so a build never downloads),
- `Config.SHOPIFY_API_VERSION` in `config/Config.kt`, the version the service speaks (a constant, not a setting),
- nothing in `test/`: `shopifyGraphqlUrl(port)` and `shopifyAdminGraphqlUrl(shop)` derive the version from
  `Config.SHOPIFY_API_VERSION`. Two literals are deliberate and stay: the URL-shape
  assertion in `OutBoundShopifyOAuthPathsTest`, and the off-default version in
  `HttpShopifyGraphqlServiceFactoryTest`, which exists to prove the factory uses the version it was configured with.

Then `./gradlew graphqlIntrospectSchema graphqlGenerateClient`, commit the new `schema.graphql`, and fix the drift the
compiler reports (removed fields, renamed enums). Introspection reads Shopify's public proxy and needs no token.

Codegen refuses deprecated fields in an operation's selection (`allowDeprecatedFields = false`), so what the new
version deprecates is a build error rather than a removal to be surprised by a year later. It cannot see deprecated
input fields, which introspection leaves out of the schema, nor what Shopify deprecates after the schema was fetched:
Shopify reports those per request, and `ShopifyDeprecationWarnings` logs them once per operation ("Shopify reports
deprecated API use").

## Running operations
- `ShopifyGraphqlService` is the only place operations are executed. Each method is a single-shot primitive — one per
  `.graphql` file — answering a typed `ShopifyResult<T>` (result4k `Result<T, ShopifyError>`);
  `HttpShopifyGraphqlService` is the implementation. Multi-step behaviour (scan-then-register, load-then-create) is a
  `workflow/` function composing primitives.
- The triage happens once, in `HttpShopifyGraphqlService.execute` and the method that owns the payload: a thrown
  transport failure is `ShopifyError.Network`, a body the generated types cannot read is `ShopifyError.Undecodable`,
  top-level `errors` (or a missing `data`) are a `ShopifyError.GraphqlError` carrying their `extensions.code`s, a
  mutation payload's `userErrors` are `ShopifyError.UserError`, an absent resource is `ShopifyError.NotFound`. Each knows
  whether a retry can help (`isRetryable`). A caller pattern-matches on `Success` / `Failure` and never reads
  `response.errors`.
- A method answers our own types where it can (`ShopIdentityInfo`, `ShopifyFulfillmentId`, `WebhookSubscriptionStatus`)
  and a generated snapshot only where a mapper needs the whole thing (`Order`, `Product`).
- The client never sends a nullable variable whose value is `null`: graphql-kotlin's serializer sets
  `encodeDefaults = false` after applying any configuration of ours, and an input field left out is, to Shopify, a
  field not provided, which an update keeps as it is. Where a `null` must reach Shopify as a `null`, such as clearing
  an input field, declare the variable with a `= null` default in the operation (`UpdateWebhookSubscription`).
- Outside `lib/shopify/`, only the layers `ArchitectureTest.graphqlGeneratedAllowList` names may import
  `dropnext.graphql.generated.*`: `domain/fulfillment/` (the matcher), `mapper/` and `workflow/`, which walk the
  snapshots the interface answers. Handlers, routing and presentation program against the interface's own types; a
  new layer needs an allowlist entry with a one-line reason.
- Never retry Shopify traffic: it uses the base HTTP client on purpose, because a retried mutation is a duplicated
  fulfillment. The monolith client is the one with retries.
- A `ShopifyGraphqlService` is bound to one shop and its token; get one through `ShopifyGraphqlServiceFactory.forShop`
  and treat `null` as "no Admin token".

## Naming
`Graphql` / `Gql` / `gql` in our identifiers, never `GraphQL` or `GraphQl` (full rule under "Naming" in `CLAUDE.md`).
Upstream types keep their casing in imports (`GraphQLKtorClient`, `GraphQLClientResponse`).

## Tests
- In-memory: `FakeShopifyGraphqlService` (stub a `*Result` field with `Success(...)` / `Failure(ShopifyError...)`,
  read the recorded inputs) for handler and workflow tests.
- Wire-level: `FakeShopifyGraphqlServer` records each POST by `operationName` and serves the response registered for
  it; use `shopifyRewritingHttpClient(server.port)` so `HttpShopifyGraphqlService` keeps its real URL.
- A new operation needs: the `.graphql` file, a `ShopifyGraphqlService` method answering a `ShopifyResult` of our own
  type and its `HttpShopifyGraphqlService` implementation (which does the triage), a `FakeShopifyGraphqlService` result
  field, and a wire-level test with a non-empty response.
