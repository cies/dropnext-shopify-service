# Spec: notice when the contract copy has drifted

Status: draft
Author: cies (with Claude)
Date: 2026-09-16
Depends on: [010](010-prove-both-shared-secrets-before-taking-traffic.md), because fetching the monolith's
document needs the key that spec proves. Otherwise independent.
Repos: DSS only. The monolith already serves the document.


## Problem

The DSS compiles against `src/resources/monolith-dss-openapi.json`, a checked-in copy of the document the
monolith serves at `/api/shopify-service/v1/openapi.json`. Refreshing it is a manual step, done by hand in the
same work session as the monolith change. Nothing compares the two, ever. The workspace file singles this out as
the coupling that breaks silently, and the failure it produces is the worst kind: a `200` whose body the other
side cannot read, surfacing as `MonolithError.Undecodable` at the moment an order is being mirrored.

The copy is honest today. Nothing keeps it that way.


## Why not refuse to start on any difference

The obvious check, fetch the document at startup and exit when it differs, is the wrong severity, for three
reasons that are worth writing down because the idea keeps coming back.

**It would forbid the deploy order the repos prescribe.** The rule is provider first: the monolith deploys, then
the DSS. Between those two deploys the served document and the checked-in copy differ by construction, on
purpose. A strict check turns that window into a DSS outage, and the window is every monolith deploy.

**It would forbid the additive changes the repos deliberately support.** The shipment-sync spec
(`../shipment-sync-by-tracking-number/020-cancel-replaced-fulfillments.md`) adds a request field that is
optional and nullable precisely so either side can deploy first, and it leans on `ignoreUnknownKeys` in the other
direction. A byte comparison rejects exactly the change that was designed to be safe.

**It couples this service's startup to the monolith's availability.** A monolith that is down would stop every
DSS task from starting, including tasks that would have served happily from their token cache.

A narrower comparison, over only the operations and schemas the DSS actually uses, fixes none of the three: adding
a nullable field to a DTO the DSS reads is both a difference and a safe change.


## What changes

Two checks, at the two moments where each is cheap.

**A build-time comparison, which is the real fix.** The sibling layout is load-bearing here: when
`../dropnext-monolith` is present, a Gradle task regenerates or reads the monolith's document and diffs it
against the checked-in copy, failing the build on a difference. When the sibling is absent, as in CI for this
repo alone or in the Docker build, the task is skipped with a reason rather than failing. This catches the only
cause that matters, a human who changed the monolith and forgot the copy, at the moment it happens and with the
diff in front of them.

**A startup comparison that warns and serves, which is the part this spec is really about.** After the checks of
`010` have proved the key, one `GET` to `OutBoundMonolithPaths.apiPathPrefix` + `/openapi.json`, compared against
the checked-in copy. Three outcomes, and **none of them can stop the task**:

- identical: one `info` line, nothing else;
- different: one `warn` line per difference, then one `warn` summary line. The task takes traffic;
- not fetchable, whatever the reason: one `warn` summary saying so, and the task takes traffic. A document the
  DSS could not read says nothing about the contract.

`warn` rather than `error` on purpose. Drift is a thing to fix in the next work session, not to wake someone for,
and the window right after a monolith deploy is drift by design. An `error` level here would train its reader to
ignore it, which is the opposite of what the check is for.

### What a difference line says

The point of the check is that a reader can see **which** bits are misaligned without opening two documents, so
each difference gets its own line rather than being counted into a total. The lines keep the `key=value` shape
Logflare queries are written against, and each names what kind of difference it is, where it sits, and which side
has what:

```
Contract drift kind=path location=/orders side=monolith_only
Contract drift kind=operation location="DELETE /product-variants" side=copy_only
Contract drift kind=schema location=CreateShopifyOrderRequest side=monolith_only
Contract drift kind=property location=CreateShopifyOrderRequest.email side=monolith_only
Contract drift kind=required location=Shipment.carrier monolith=true copy=false
Contract drift kind=type location=OrderLineItem.quantity monolith=string copy=integer
Contract drift kind=server_url monolith=/api/shopify-service/v2 copy=/api/shopify-service/v1
```

Then one summary, which is the line to alert on if anything ever alerts on this:

```
Contract drift summary differences=7 shown=7 paths=1 operations=1 schemas=1 properties=2 required=1 types=1 urls=1
```

`kind=server_url` earns its place: `servers[0].url` is what `generateOutBoundMonolithPaths` turns into
`OutBoundMonolithPaths.apiPathPrefix`, so a change there moves every outbound call at once.

### What is compared, and what is not

The comparison is over the parsed JSON, not the bytes, so key order and whitespace do not matter. It compares the
server URL, the paths and their operations, the schema names, and per schema the property names, their types,
their nullability and whether they are required. It never compares descriptions, summaries, examples or the
`info` block: those are prose the monolith edits freely, and a check that fires on a reworded sentence is a check
nobody reads.

**A cap on the lines.** At most `MAX_LOGGED_CONTRACT_DIFFERENCES` of them, twenty or so, then the summary says how
many were found and how many were shown. Without it a monolith serving something wholly different, a document
from another service or an error page that happened to parse, would put thousands of lines into one startup.

The fetch is bounded by the existing monolith client timeout and runs inside the preflight budget, so a slow
monolith costs nothing but the step.


## Behavioral contract

- **Precondition**: a reachable monolith and an accepted key, both established by `010`. Neither is required for
  the task to start.
- **Postcondition**: a drifted contract is visible in the log within one startup, as one `warn` line per
  difference naming its kind and its location, followed by one `warn` summary.
- **Invariant**: this check never stops the service from starting, never delays it past the preflight budget, and
  never changes an answer it gives. It is a smoke alarm, not a valve. There is no setting that makes it one.
- **Invariant**: every line it writes is at `warn` or `info`. It never writes at `error`, because nothing it can
  discover is worth waking someone.
- **Invariant**: an additive, backward-compatible monolith change is reported as a difference and is not an
  error at build time either, when it only adds paths or optional fields. See open question 1.
- **Invariant**: the build-time task never reaches the network and never rewrites the checked-in copy. Refreshing
  the copy stays a deliberate act.


## Edge cases

- **The sibling checkout is on another branch.** The build-time diff then compares against that branch's monolith
  and can fail for a change that is not on either mainline. It reports which checkout and which revision it read,
  so the reader can tell at a glance. Open question 2 asks whether it should compare only when the sibling is on
  its default branch.
- **The monolith serves a document the DSS cannot parse.** Reported as not fetchable, not as drift.
- **The monolith serves a valid but wholly unrelated document.** Every path and schema differs, so the cap is what
  keeps the startup log readable: twenty lines and a summary saying how many were found.
- **The DSS is deployed while the monolith is mid-deploy**, so the document is the old one. The startup check
  reports drift that resolves itself minutes later. It is a log line, not a page, which is why it warns rather
  than blocking.
- **The prefix is already inside `MONOLITH_BASE_URL`**, as in staging. The URL is built the same way every other
  monolith call is built, through `prefixedBase`, so it inherits whatever that resolves to.
- **The document is large.** It is fetched once per task start and compared in memory; nothing keeps it.


## Reuse inventory

- `HttpMonolithService.prefixedBase` and its client, which already carries the bearer and the trace id.
- `OutBoundMonolithPaths.apiPathPrefix`, generated from the same document.
- `MonolithJson` for parsing, and `MonolithError` for reporting a fetch that failed.
- `preflightBeforeTakingTraffic` and `PreflightReport` in `boot/preflight/`, renamed by
  [010](010-prove-both-shared-secrets-before-taking-traffic.md), which already run outbound steps at startup and
  report each as ok, failed or skipped. This check is a third kind: it neither warms nor decides, so it reports
  beside the checks and can never stop the task.
- `DiagnosticsHandlers` and the `/health` body, which already carries a status and a version.
- `generateOutBoundMonolithPaths` in `build.gradle.kts`, which already parses the checked-in document at build
  time and is the natural place for the sibling diff to live beside.


## Test plan

- **Pure**: the comparison itself, which is the only part worth real coverage. Identical documents; a document
  with an added path; one with a removed path; one with a changed required field; one with a changed property
  type; one with a different `servers[0].url`; one whose descriptions differ only, which is not a difference; one
  whose keys are in another order, which is not a difference either. Each case asserts the `kind` and the
  `location` of the line it produces, since those are what a reader acts on.
- **Fake-backed** (the renamed preflight test): a monolith serving the checked-in document reports
  identical; one serving a modified document writes one `warn` per difference and the summary, and the preflight
  still succeeds; one answering `404` or a timeout reports not fetchable at `warn`; one serving a document that
  differs in more ways than the cap writes the cap and a summary naming the true total.
- **Log** (through the suite's captured-logs helper): a drifted startup writes every difference at `warn` and
  nothing at `error`. This is the whole observable behavior of the check, so it is the assertion that matters
  most.
- **Build**: the Gradle task fails on a seeded difference and is skipped with a reason when the sibling directory
  is absent. Worth one test only if the task is written in a testable shape.


## Open questions for the human developer

1. **Should the build-time task fail on any difference, or only on a breaking one?** Failing on any difference is
   simple and makes the refresh a habit. Failing only on a breaking one, a removed path or operation, a removed
   or newly required field, a changed type, permits the additive monolith deploy that lands first and keeps the
   DSS building in between. The second is proposed, with the diff printed either way.
2. **Should the sibling diff run only when `../dropnext-monolith` is on its default branch**, to avoid failing
   against someone's feature branch? Skipping with a reason is the alternative, and it is quieter but easier to
   ignore.
3. **Should the drift also be reported on an endpoint, not only in the log?** The spec says log only, which is
   what was asked for. If it is wanted somewhere a human can curl, `/api` is the diagnostics surface and the
   better home than `/health`, which a load balancer reads and acts on.
4. **Is twenty the right cap** on difference lines? It is enough to read a normal drift in full and few enough
   that a wholly wrong document cannot flood a startup.
5. **Should the check compare only the parts the DSS uses**, rather than the whole document? Narrowing it would
   hide a monolith change to an endpoint the DSS does not call yet, which is exactly the change most likely to be
   forgotten later. Comparing everything is proposed.
