
### 51. Gaps in the race rule, and `checked_at` can move backward [RI M7]

- **Spec:** `030` (the `observed_at` race rule), `050` ("the later `checked_at` wins").
- **Where:** `src/dropnext/workflow/shopify/recordStoreShopifyObservation.kt:79,144-147`.
- **What is wrong:** The rule as specified is implemented and tested. Its edges:
  - An observation that learned nothing (the DSS did not answer, or the scopes were `unknown`) still advances
    `checked_at` and sets a check trigger, so a genuine report observed just before it is then dropped as outdated.
  - Once any report is recorded, a stale report from before the install is no longer recognized, because the previous
    row is a `DssReport`.
  - A late report, or two overlapping checks, write an older `checked_at` over a newer one. The last writer wins, not
    the later observation that `050` promises.
- **Suggested solution:** Compare reports against a separate "last install or check that learned something" time, and
  never let `checked_at` go backward. Or accept the edges and correct the `050` sentence.

### 52. What the re-enqueue after a clear does not cover [RI M8]

- **Spec:** `030` ("re-enqueue after the fix").
- **Where:** `src/dropnext/db/sql/unsyncedShipmentsRead.kt:171-185`; `src/dropnext/workflow/shopify/recordStoreShopifyObservation.kt:122-124`.
- **What is wrong:**
  - Only orders with a *live* unsynced shipment are queued again. An order whose only refused work was cancelling the
    fulfillment of a retired shipment is not.
  - Stores that were already broken before this rollout have no flagged row, so their first install clears nothing
    and queues nothing.
  - Orders whose shipments never fit are queued again on every clear, which is cheap but also happens daily in the loop of issue 3.
- **Point for the human developer:** Decide whether a one-off "sync every unsynced order of this store" (an admin
  action, or a step of the rollout) is wanted, and whether the query should also take retired shipments.

### 53. Deviation not recorded: a `401` on a connected store badges "Reconnect needed" [RI M9]

- **Spec:** `040` (behavioral contract: "the badge `Not connected`").
- **Where:** `src/dropnext/workflow/shopify/checkStoreShopifyStatus.kt:65-66`; `src/dropnext/domain/StoreShopifyStatus.kt:54-64`.
- **What is wrong:** Nothing is wrong in the behavior. A store with a stored token that the DSS has no token for does
  need the merchant, so flagging it is sound. But the `040` appendix does not list this as a deviation; only the `030`
  appendix explains it, in passing.
- **Suggested solution:** Correct the contract line in `040`, or add the deviation to its appendix.

### 54. DSS-supplied strings are taken as they come [RI M10]

- **Spec:** `030` (security).
- **Where:** `src/dropnext/handler/api/dss/apiHandlers.kt:271-284`; `src/dropnext/domain/StoreShopifyStatus.kt:103-107`.
- **What is wrong:** `missing_access_scopes` of a report goes unchecked into the reason, the notification markdown,
  the flash and the row. Every place that renders it escapes it (kotlinx.html text and attributes, and the XSS-safe
  markdown generator), so this is not an injection. But a DSS bug could store arbitrary text of any length.
  `observed_during` is logged cut to 64 characters, without quoting.
- **Suggested solution:** Keep only the values that match `ACCESS_SCOPE_HANDLE` (the regular expression `accessScopeHandlesIn` uses), and log
  `observed_during="…"` quoted, as the DSS does.

### 55. The job's refusal logging and dead-letter volume [RI M11]

- **Spec:** `050`.
- **Where:** `src/dropnext/job/CheckStoreShopifyStatus.kt:57-58`.
- **What is wrong:** Every `Rejected` is logged at `info`, including a `404` (a DSS without the route, as in the
  deploy window of issue 5) or a `400`, which point at a bug.
  If the DSS is down for longer than the retry schedule, every connected store's check is dead-lettered, and each
  dead letter notifies the admins: one notification per store.
  `050` says "three attempts"; the job gets the default five (about 36 minutes).
- **Suggested solution:** Log refusals other than `401`/`403` at `warn`, and correct the attempt count in `050`.
  Decide whether a dead-lettered status check should notify admins at all; the next day's run repeats it anyway.

### 56. The install link relies on `DSS_BASE_URL` being the DSS's public origin [RI M12]

- **Spec:** `040` (the install link is built only from `DSS_BASE_URL` and the subdomain).
- **Where:** `src/dropnext/lib/dss/DssWebhookService.kt:252-253`; `dropnext-infra/environments/prod/terraform.tfvars:23`
  (still `<TODO_DSS_BASE_URL_PROD>`); `dropnext-infra/environments/staging/variables.tf:125-126`.
- **What is wrong:** The requirement itself is met: the link is built from the base URL and the subdomain alone, and
  is URL-encoded. But the monolith's `DSS_BASE_URL` is the address the monolith *calls*. If it is ever pointed at an
  internal address (service discovery, an internal load balancer), every link shown to merchants and sent in
  notifications breaks. The staging variable's description states the coupling; nothing in the monolith does.
- **Point for the human developer:** Fill in the production value as the public origin, and either state the coupling
  next to `dssBaseUrl` in `envConfig.kt` or give the link its own variable.

### 57. Test gaps [RI M13]

- **Spec:** `030`, `040`.
- **Where:** `test/dropnext/workflow/shopify/CheckStoreShopifyStatusDbTest.kt`,
  `test/dropnext/workflow/shopify/RecordStoreShopifyObservationDbTest.kt`,
  `test/dropnext/integration/api/dss/StoreShopifyStatusApiE2eTest.kt`.
- **What is missing:**
  - A test that a token-rejected report followed by a check that reads `401` notifies once. This is the stated reason
    for the single token text (judgment (b)).
  - A test that a check (not an install) that clears the flag queues the syncs again.
  - The same install sent twice leaving one notification (the idempotence of `PUT /stores/api-key` with a short grant).
  - The bare `401` of issue 1.
- **Suggested solution:** Add the four cases in the Red phase; issue 1's fix comes with its own.

### 58. Naming, prose and doc comments [RI M14]

- **Spec:** all four.
- **What is wrong:**
  - `store_shopify_statuses.status_detail` is an external body kept verbatim. Column rule 16 names that `payload`
    (for example `status_payload`).
  - `checked_at` also holds the DSS's time for a report, which is not a check; `observed_at` would say what the
    column comment has to explain.
  - The admin path and handler are `registerShopifyStoreWebhooks`; the retailer's, and the workflow, are `registerStoreShopifyWebhooks`.
  - `db/sql/job/storeShopifyStatusChecksRead.kt` holds `selectConnectedStoreIds`, a read of `stores`.
  - Two test classes share the simple name `CheckStoreShopifyStatusDbTest` (`test/dropnext/job/` and
    `test/dropnext/workflow/shopify/`), and the job class differs from the workflow function only in case.
    Both are allowed, but they are confusing in reports and in `SlowestClassesFirstOrderer`'s ranking.
  - UI text: "Last checked 5 minutes ago by a report of the Shopify service." describes a report as a check. Something
    like "Last observed … (reported by the Shopify service)" reads true.
  - The new KDocs wrap at a fixed width, mid-sentence. The workspace `CLAUDE.md` asks for semantic line breaks.
    Some KDocs restate their name: `/** What recording an observation did. */` and
    `/** Writes the store's whole status row, as [status] states it. */`.
  - No British spelling and no spec references were found in the new code, SQL or UI text.
- **Suggested solution:** Rename where cheap (the column is not deployed yet, so now is the moment), and leave the rest as review notes.

### 59. `git status` may write in the sibling checkout, and the five-second guard does not guard [SR 8]

- **Spec:** 020.
- **Where:** `src/dropnext/dss/lib/monolith/drift/checkContractCopy.kt:96` and `:101-108`.
- **What is wrong:** `git status` takes the optional index lock and may rewrite `.git/index` in the monolith checkout.
  That can collide with the human's own git work there, and it contradicts "reads two files and writes none".
  Separately, `readText()` blocks until git closes its output, so the guard `waitFor(5, SECONDS)` is never reached while
  git hangs.
- **Suggested solution:** run `git --no-optional-locks -C …`. Call `waitFor` before reading, or redirect the output to a
  temporary file, and kill the process on a timeout.

### 60. Holes in "the whole document" [SR 9]

- **Spec:** 020.
- **Where:** `compareContractDocuments.kt:164-178` and `:199-202`, plus `:135` and `:99-107`.
- **What is wrong:**
  - Properties and enum values inside inline `anyOf`, `oneOf` and `allOf` members are not walked (the copy has one
    `anyOf` today, a `$ref`, so nothing is missed yet).
  - `additionalProperties`, path-level `parameters`, `$ref`'d parameters and responses, and the security schemes are not
    compared. A change of auth scheme, like the one in `a9c34bb`, would pass unseen.
  - The API root is inferred from the path keys rather than compared with `monolithDssApiRoot`. When every remaining
    path shares a deeper segment, the inferred root is wrong and a breaking `api_root` line is reported falsely.
- **Suggested solutions:**
  - Descend into combinator members.
  - Compare `securitySchemes` by type and scheme, and `additionalProperties` as part of the type.
  - Pass the expected root in. The build task can hand over `monolithDssApiRoot`, and the startup check can use a
    constant generated beside `OutBoundMonolithPaths`.

### 61. A fetch that the budget cuts short writes no "not checked" line [SR 10]

- **Spec:** 020.
- **Where:** `warmUpBeforeTakingTraffic.kt:97` with `:184-187`.
- **What is wrong:** `withinBudget` returns `Failed("budget")` without running the `Contract drift not checked` branch. Such
  a start leaves only `contract=failed error=budget` on the outbound line. The spec says "not fetchable, whatever the
  reason: one `warn` summary saying so".
- **Suggested solution:** log the `not checked` line from the step's result in the caller, so a budget cut gets it too.
  Add a test with a delayed `openApiDocumentResult`.

### 62. The cap can hide the breaking lines [SR 11]

- **Spec:** 020.
- **Where:** `compareContractDocuments.kt:36` and `warmUpBeforeTakingTraffic.kt:149`.
- **What is wrong:** the lines are sorted by kind, so twenty additive `path` lines can push every breaking `property` line
  past the cap. The summary still counts them.
- **Suggested solution:** put the breaking differences first, then sort by kind.

### 63. The production exit sits in the default dependency graph that tests build [SR 12]

- **Spec:** 010.
- **Where:** `src/dropnext/dss/dependencies.kt:198`.
- **What is wrong:** no test uses `deps.warmUp` today. But `withDssApp(deps, warmUp = deps.warmUp)` with a fake that
  answers `401` would call `exitProcess(1)` inside the Gradle test JVM.
- **Suggested solution:** make the exit a `dssDependencies` parameter, and have `testDependencies` pass a recorder.
  Alternatively, add a `TestSuiteArchitectureTest` rule against `deps.warmUp` in `test/`.

### 64. Naming and prose under the narrow rename [SR 13]

- **Spec:** 010.
- **Where:** `warmUpBeforeTakingTraffic.kt:162`, `WarmUpReport.kt:22`, `warmUpBeforeTakingTraffic.kt:199-205` and
  `CLAUDE.md:337`.
- **Is the narrow rename coherent?** Mostly, yes.
  - The package, types and log prefix all say "warm-up". The KDocs (`WarmUpReport`, `WarmUp`, `startWarmUp`) and
    `CLAUDE.md` each say that two of its steps are checks that decide traffic.
  - `checkMonolithAcceptsOurKey` and `warmInboundPipeline` name what they do.
- **What does not hold:**
  - `checkMonolithKeyReachesUs` names a guarantee the step does not give (issue 2).
  - The log fields changed now (`monolith=` → `check_monolith_accepts_our_key=`, `api_check=` →
    `check_monolith_key_reaches_us=`) while the prefix stays. The deferred full rename will break saved queries a second
    time.
  - The opening paragraph of "Warm-up and readiness" in `CLAUDE.md:337` still describes the outbound part as "one
    `getStore`". It now also fetches the document.
- **Suggested solutions:**
  - Rename the inbound step together with issue 2.
  - Either keep the old field names until the full rename, or schedule the full rename soon, so the queries change once.
  - Add the document fetch to the paragraph in `CLAUDE.md`.

### 65. Test-suite conventions and coverage gaps [SR 14]

- **Spec:** both.
- **Where:**
  - `test/dropnext/dss/boot/warmup/HttpWarmUpLoopbackServiceTest.kt:54-91`
  - `test/dropnext/dss/boot/warmup/WarmUpBeforeTakingTrafficTest.kt`
  - `test/dropnext/dss/lib/monolith/drift/CheckContractCopyTest.kt`
  - `.claude/rules/tests.md:45` and `:47`
- **What is wrong:**
  - The two new loopback tests start and stop `FakeMonolithHttpServer` by hand, while `tests.md` says
    `withFakeMonolithServer` is for "every test that needs one". The old test in the file does the same, so the pattern
    was copied.
  - These cases are not covered:
    - both keys rejected at once (the combined reason);
    - a document fetch answered `404` (the spec's test plan names it);
    - a monolith `Undecodable` on `getStore`, which must be `Failed`, not `Rejected`;
    - the exit status of `checkContractCopy`'s `main`.
  - `tests.md` still says `testConfig()` has "two secrets", and its fixture row does not list
    `TEST_DSS_TO_MONOLITH_API_KEY`.
- **Suggested solutions:** move the new tests onto `withFakeMonolithServer`, add the four cases and update the two lines
  in `tests.md`. For `main`, the check can return an exit code that a thin `main` passes to `exitProcess`, and the code
  can be tested.

### 66. Outcome-type naming [SR 15]

- **Spec:** 020.
- **Where:** `src/dropnext/dss/lib/monolith/drift/ContractDifference.kt:54` and `checkContractCopy.kt:14`.
- **What is wrong:** `CLAUDE.md` ("Naming") calls a summary of many a `*Report`. `ContractComparison` is one, and
  `ContractCopyVerdict` and `WarmUpVerdict` add a third word.
- **Suggested solution:** rename `ContractComparison` to `ContractDriftReport`. Alternatively, accept "verdict" for a
  decision and add it to the naming list.

### 67. A missing contract resource would disable the key checks [SR 16]

- **Spec:** 020.
- **Where:** `warmUpBeforeTakingTraffic.kt:70` and `readCheckedInContractCopy.kt:15`.
- **What is wrong:** the copy is read as a default argument, before any step runs. If it throws, the whole warm-up throws,
  `startWarmUp` logs "taking traffic anyway", and neither key check has run. That is unlikely, since the build needs the
  file, but it is a reporting step able to switch off a deciding one.
- **Suggested solution:** read the copy inside `compareContractWithMonolith`, and turn a failure into
  `Failed("copy_unreadable")`.

### 68. The events connection has no `pageInfo`, which the Graphql rules require [TD 3]

**Where:** `src/resources/GetFulfillmentEvents.graphql:4-10`.
The rule is in `.claude/rules/graphql.md:72`.

**What is wrong.**
The rule says that a connection which can run past one page asks for `pageInfo`.
The owning method then answers `Truncated`, or the cursor for a workflow that pages.
This query does neither.
The choice is deliberate and reasoned in the KDoc at `HttpShopifyGraphqlService.kt:85-91`, and refusing the page would be
wrong here.
But the rule file does not mention the exception.
The spec's claim that "a fulfillment with more than 50 events does not exist in practice" also cannot be observed.

**Suggested solutions.**
- Ask for `pageInfo { hasNextPage }` and log at `info` when it is `true`, for example
  `Fulfillment events past one page fulfillmentId=… pageSize=50`, still without refusing the page.
  This costs almost nothing, and it makes the claim countable in Logflare.
- Or add one line to `graphql.md` naming this query as the documented exception.

### 69. The message is left out of the match, but no reachable case needs that [TD 4]

**Where:** `syncShopifyTrackingEvent.kt:74-76`; spec, "Behavioral contract".

**What is wrong.**
The cases the spec targets are all redeliveries of one `ForwardTrackingUpdateToDss` job.
That job's payload is immutable, so a redelivery always carries the same message.
The spec's reason for ignoring the message is a carrier rewording an event.
A reworded event comes from Ship24 with a new `eventId`, and the monolith's no-regress filter
(`status_updated_at < :happened_at` in `shipmentsWebhookWrite.kt`) drops one at the same instant before it is enqueued.
So ignoring the message guards nothing that can reach the DSS.
It does have a cost.
Two different checkpoints with the same status, in the same second but with different fractions, are both forwarded,
because the second is strictly newer.
The DSS then drops the second one.
This is rare, since Ship24's `datetime` is usually at minute precision with `.000`.

**Decision for the human.**
Keep the spec as written, or add the message to the match when both sides have one.
The latter needs `message` in the query, and it depends on Shopify storing the message unchanged (whether it trims is
not verified).
