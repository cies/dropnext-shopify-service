# Spec: prove both shared secrets before taking traffic

Status: draft
Author: cies (with Claude)
Date: 2026-09-16
Depends on: nothing. Prerequisite for [020](020-notice-when-the-contract-copy-has-drifted.md) only in the sense
that both add a verdict to the same startup path.
Repos: DSS only. No contract change. The infra already carries both keys in the DSS secret
(`../../../dropnext-infra/modules/app-dss-config/outputs.tf`), so nothing changes there.


## Problem

Two bearer secrets guard the traffic between the services, one per direction, and neither is checked against the
other side before the task takes traffic.

`DSS_TO_MONOLITH_API_KEY` is **optional** here and **required** there. `Config` reads it as a nullable value and
`HttpMonolithService.applyDefaults` sends the header only when it is set and non-blank, while the monolith binds
its whole DSS API behind `dssApiAuthOnRequest`. So an unset key, or one rotated on one side only, makes every
monolith call answer `401`. What that does depends on the path, and none of it is loud:

- **An order webhook.** `MonolithError.Rejected(401)` is not transient, so the delivery is answered `200` and
  Shopify never redelivers. Every order is lost, silently, for as long as the key is wrong.
- **A token lookup.** `resolveShopTokenFromMonolith` answers `ShopLookup.Unavailable`, so webhooks answer `502`
  and Shopify redelivers until it removes the subscription.
- **The warm-up.** `warmMonolith` records `Failed("monolith_401")`, writes one `warn`, and `startWarmUp` marks
  the task ready anyway in its `finally`. A task that cannot talk to the monolith at all joins the load
  balancer's rotation looking healthy.

`MONOLITH_TO_DSS_API_KEY` is required and length-checked here, but nothing proves the monolith holds the same
value. When it does not, every monolith-to-DSS call answers `401` and the shipment sync and tracking updates stop.
The warm-up's loopback `apiCheck` cannot see it: `toStepOutcome` treats any `401` as a healthy pipeline, because
it cannot tell "this shop has no Admin token", which is the expected answer for the placeholder shop, from "your
bearer token is wrong".

**Does making the key required help?** Partly, and it is worth doing. It turns the absent case into a refusal to
boot, which is the loudest and cheapest possible signal. It does nothing for the case that actually happens,
which is a key that is present and wrong after a rotation. That needs a check against the other side.


## What changes

- **`boot/config/Config.kt`** — `dssToMonolithApiKey` becomes non-nullable and required, validated by the
  existing `requireUsableBearerSecret`. The README's variable table changes with it.
- **`lib/monolith/HttpMonolithService.kt`** — the key is no longer nullable and the `Authorization` header is
  always sent. The `takeIf { it.value.isNotBlank() }` guard goes.
- **The outbound warm-up step is replaced by a check.** It makes one `getStore` for the placeholder shop and
  throws the answer away. The check makes the same call and reads it. Nothing is added and nothing is paid twice:
  warming the connection was always the side effect of making a call, and the check makes it. What goes is the
  step whose only purpose was the side effect. The verdict:
  - `Success`, which includes the `404` the monolith answers for the placeholder shop, proves the key is
    accepted. The step is `Ok`, as today.
  - `Rejected` with `401` or `403` proves the key is wrong. **The process exits non-zero.**
  - Anything else, a transport failure, a `5xx`, a timeout, proves nothing about the key and is reported as it is
    today. The task takes traffic, because a monolith that is down must not stop the DSS from serving the shops
    whose tokens it already holds.
- **The inbound `/api/check` step becomes a check too**, and learns to tell the two `401`s apart. A rejected bearer is a bare `401` carrying
  `WWW-Authenticate: Bearer realm=dss-internal` and no body; a shop without an Admin token is a `401` carrying
  the JSON `ApiError`. The loopback client reads the header, and a rejected bearer exits non-zero the same way.
  This is what proves `MONOLITH_TO_DSS_API_KEY` end to end, since the request goes through the real auth plugin.
- **`/health`** keeps its two states. A task that exits never answers at all, which is the point: a rolling
  deploy leaves the old task serving.
- **The self-signed webhook delivery stays a warm-up, and keeps the name.** It proves nothing and decides
  nothing. It exists so that every plugin and the HMAC have run once before a real delivery arrives, which is
  exactly what a warm-up is for.


## Naming: a check is not a warm-up

Two of the three steps change purpose here, from warming to verifying, and the code has to say so. A step whose
answer decides whether the task takes traffic is a check. Leaving it in a package called `warmup`, behind a
function called `warmMonolith`, names it after its side effect and hides the half that can stop a deploy. The
next reader would reasonably assume that nothing in there matters.

So the package is renamed to an umbrella that covers both kinds, and the two kinds are named apart inside it.
`boot/startup/` would read as a tautology beside `boot/`, so the umbrella is `preflight`: the checks and the
preparation done before taking off, which is what this package has become.

| Today | Becomes |
|---|---|
| `boot/warmup/` | `boot/preflight/` |
| `warmUpBeforeTakingTraffic` | `preflightBeforeTakingTraffic` |
| `WarmUp`, `WarmUp.NONE` | `Preflight`, `Preflight.NONE` |
| `startWarmUp` | `startPreflight` |
| `WarmUpReport`, `WarmUpOutboundReport`, `WarmUpInboundReport` | `PreflightReport`, and its two halves renamed with it |
| `WarmUpStepOutcome` | `PreflightStepOutcome` |
| `WARM_UP_BUDGET`, `WARM_UP_PLACEHOLDER_SHOP` | `PREFLIGHT_BUDGET`, `PREFLIGHT_PLACEHOLDER_SHOP` |
| `WarmUpLoopbackService`, `HttpWarmUpLoopbackService`, `FakeWarmUpLoopbackService` | `LoopbackService`, `HttpLoopbackService`, `FakeLoopbackService` |
| `warmMonolith` | `checkMonolithAcceptsOurKey` |
| the loopback `/api/check` step | `checkMonolithKeyReachesUs` |
| the loopback webhook delivery step | `warmInboundPipeline` |

The loopback service loses the `WarmUp` in its name on purpose. It sends this service a request over `127.0.0.1`;
whether the caller is warming or checking is the caller's business, not its own.

`PreflightReport` groups its steps by kind rather than by direction, because the kinds are what a reader acts on:
a failed check can stop the task, a failed warm-up never does. That changes the two log lines from
`Warm-up outbound done …` and `Warm-up inbound done …` to one line per kind, `Preflight checks done …` and
`Preflight warm-up done …`, each keeping the `key=value` shape the existing lines have.

Carried along, because they name the thing being renamed: the `ArchitectureTest` rules that pin the package's
imports and that only one function marks the service ready, the "Warm-up and readiness" section of `CLAUDE.md`,
the project-structure table in the same file, and the README. The test files rename with their subjects, which
`TestSuiteArchitectureTest` enforces anyway.


## Behavioral contract

- **Precondition**: a configuration that parsed, so both keys are present and are usable bearer tokens.
- **Postcondition**: a task that answers `/health` with `status=ok` has proved that the monolith accepts our key
  and that we accept the monolith's, or that the monolith could not be reached to say either way.
- **Invariant**: a wrong shared secret never reaches the load balancer's rotation. The process exits instead.
- **Invariant**: a monolith that is merely unavailable never stops this service from starting. The two failures
  are told apart by `MonolithError`, which already carries the distinction.
- **Invariant**: the checks change nothing upstream. The placeholder shop is one the monolith answers `404` for,
  the loopback check is read-only, and no token is remembered or forgotten.


## Edge cases

- **The monolith is mid-deploy when the DSS starts.** Connection refused is a transport failure, so the task
  starts and serves. The first real webhook then re-reads the token through the same path.
- **The monolith answers `401` because its own configuration is broken**, not ours. Indistinguishable from here,
  and the answer is the same: this task must not take traffic while every call it makes fails.
- **A store actually named `dss-warm-up`.** The lookup then succeeds with a row instead of a `404`. Still a
  proof that the key is accepted, which is all the check reads.
- **A crash loop.** A wrong key makes every task exit at startup, so ECS restarts them and the deployment never
  goes healthy. That is the intended shape: the old task keeps serving and the deploy rolls back rather than
  half-succeeding. It is also the risk worth naming, since a monolith that answers `403` for an unrelated reason
  would take the DSS down with it. Open question 2.
- **A rotation done in the right order**, monolith first with both keys accepted for a window, never trips this.
  A rotation done in the wrong order stops the next deploy, which is the point.
- **Saved log queries break.** Anything searching Logflare for `Warm-up` stops matching the moment this deploys.
  The rename is the point of the change, so the fix is to update the saved queries, not to keep the old prefix;
  worth doing in the same session rather than discovering it during an incident.


## Reuse inventory

- `warmUpBeforeTakingTraffic` and `warmMonolith` in `boot/warmup/`: the outbound step and the call it already
  makes. Both are renamed here rather than added to.
- `ArchitectureTest` and `TestSuiteArchitectureTest`, whose rules name the package and the readiness-marking
  function by string, so they are part of the rename rather than a consequence of it.
- `WARM_UP_PLACEHOLDER_SHOP`, and the monolith's `404` for it.
- `MonolithError.Rejected` with its `status`, which already carries what the verdict needs.
- `HttpWarmUpLoopbackService` in `boot/warmup/`, which already sends the monolith bearer to `/api/check`, and
  which the rename reduces to `HttpLoopbackService`.
- `installMonolithWebhookAuth`, whose `realm` is what makes the two `401`s distinguishable.
- `requireUsableBearerSecret` in `boot/config/Config.kt`, already applied to this key when it is present.
- `WarmUpStepOutcome` and `WarmUpReport`: the verdict is a new outcome, not a new report, and both are renamed
  by the section above.


## Test plan

- **Pure** (`ConfigTest`): a configuration without `DSS_TO_MONOLITH_API_KEY` is refused; one with a placeholder
  or an unparseable value is refused, as the other key already is.
- **Fake-backed** (`PreflightBeforeTakingTrafficTest`, renamed with its subject): a monolith answering `401` to `getStore` produces the fatal
  outcome; one answering `404` produces `Ok`; one that is unreachable produces the existing failure and is not
  fatal; a `500` is not fatal.
- **Fake-backed** (`HttpLoopbackServiceTest`, renamed with its subject): a bare `401` with the `WWW-Authenticate` header is fatal; a
  `401` carrying an `ApiError` body is `Ok`, as today.
- **Wire** (`HttpMonolithServiceTest`): the `Authorization` header is present on every request now that the key
  cannot be absent.
- **Startup**: whatever the suite can assert about the exit path without starting a process; if that is nothing,
  the fatal outcome is asserted at the report level and the exit is left to a manual check.


## Open questions for the human developer

1. **Exit, or stay unready forever?** Exiting is louder and gets the task replaced. Staying unready keeps the
   process alive for an operator to curl `/health` and read why. Exiting is proposed, with the reason on stderr.
2. **Is a `403` fatal, or only a `401`?** The monolith answers `401` for a bad bearer today. A `403` from
   something in front of it, a WAF or a load balancer rule, would be read as a bad key and would stop every task.
   Narrowing the fatal case to `401` is the cautious choice.
3. **Is `preflight` the right umbrella, and is the full rename wanted?** The narrower alternative keeps
   `boot/warmup/` and renames only the two step functions, which is a much smaller diff into a working tree that
   already carries uncommitted work. It leaves a package called `warmup` holding two checks that can stop a
   deploy, which is the thing this section argues against, but it is a defensible trade if the rename is better
   done on its own afterwards.
4. **Should the token lookup's `Unavailable` path be reconsidered** now that a wrong key cannot survive startup?
   It currently answers `502` and asks Shopify to redeliver, which is right for a monolith that is down and
   wasteful for one that is refusing us. After this spec the second case cannot arise at startup, but it can
   still arise from a rotation while the task runs.
