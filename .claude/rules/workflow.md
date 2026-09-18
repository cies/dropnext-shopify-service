# Implementation workflow for non-trivial changes

Referenced from `CLAUDE.md`. This is the full Verified Spec-Driven Development (VSDD) procedure.

Adapted from [Verified Spec-Driven Development (VSDD)](https://gist.github.com/dollspace-gay/d8d3bc3ecf4188df049d7a4726bb2a00).
This workflow applies when a change involves **any** of: new handlers, new or modified Graphql operations, new outbound
monolith calls, new webhook topic handling, OAuth/install flow changes, or security-relevant changes. Skip it for typo
fixes, comment updates, doc tweaks, or single-line config changes.

## Phase 1 — Spec crystallization

Before writing code, articulate:
- **What changes**: which handlers, Graphql operations, monolith calls, workflows, mappers, and paths are affected or need to be created.
- **Behavioral contract**: preconditions, postconditions, and invariants of the change.
- **Edge cases**: boundary conditions, failure modes, empty/null states (e.g. a missing Admin token, Graphql `userErrors`,
  monolith 4xx/5xx, a duplicate webhook delivery).
- **Reuse inventory**: existing functions, utilities, and patterns to reuse (with file paths).
Actively look for these — avoid writing new code when suitable implementations already exist.
- Ensure new names follow the naming scheme for handlers, routing files, services and outcome types (see "Naming" in `CLAUDE.md`).
- A change to the DSS ↔ monolith contract names both sides: the spec the monolith serves and our checked-in copy
  `src/resources/monolith-dss-openapi.json` (see `../CLAUDE.md`, "Cross-repo couplings").

Phase one starts from a human description of the task at hand.
Claude Code should request as many questions as it needs from the human developer to understand the end goal(s) -- brainstorming in Phase 1 is good.
Phase one delivers one or more --as many as makes sense-- spec files (`.md`-files in `/specs/`).
The scope of each spec possible should be as small as possible,
while describing a testable behavior that's valuable either to the "end goal" or later specs.
Human approval (and possible adjustments of the spec files) is needed to move on to Phase 2.
Spec files live in a "spec folder" named after the end goal they belong to (e.g. `/specs/add-products-update-webhook-handling`), and
their filenames start with a "multiple of 10" number left padded with zeros to a length of 3 (e.g.: 010, 020, ...)
suffixed by the spec title (e.g. `050-monolith-retry-policy.md`).
The sequence of specs should be so that each spec builds on the previous ones but does not depend on future ones.
Where possible that specs are implemented in parallel, you may describe so in the relevant specs so implementation tasks can work faster.

Before moving on from Phase 1 to Phase 2 the human developer should give approval.
Often the human developer makes changes to the spec file before moving to Phase 2.

## Phase 2 — Test-first implementation (Red → Green → Refactor)

The spec files need to be re-read as they may have been changed.

For each spec file follow these steps:

1. Clear the context *only* if more than 60%.
2. **Red**: Write failing tests FIRST for any change touching handlers, Graphql operations, mappers, or workflows.
Follow the existing test flavours, cheapest first (pure unit → fake-backed → wire-level; see `.claude/rules/tests.md`).
Each new source file gets a corresponding test file mirroring the `src/` → `test/` structure. Nothing enforces that
direction: `TestSuiteArchitectureTest` only checks that every test file has a source counterpart, so a source file
without a test is caught by review, not by the build.
3. **Green**: Write the minimal implementation to make each test pass.
4. **Refactor**: Improve clarity and remove duplication while tests stay green.
5. Write a small summary of your changes, learnings, and points may require attention of the human developer as an "implementation appendix" to the spec file.

Some functionality is not testable, in those cases the "tests first" approach may be omitted.

Compile the `main` module cleanly after each step that changes the code (typically 1, 2, and 3),
and fix compilation errors before moving on to the next step.

Adding comments to the code is appreciated, but:
* Not when they are obvious (when the code is self-explanatory or when the comment says the same as the function name plus type signature).
* Do **NOT** mention/ refer to the spec numbers or names in code (comments): spec files are **ephemeral**, they will be deleted!
  * Not in `.kt` files.
  * Not in `.graphql` files.
  * Not in the descriptions inside `monolith-dss-openapi.json`.
  * Only specs can contain mentions/ references to other specs (using spec numbers or names)!
* It's good when comments answer the question "why is this code here?" / "what it does?" / "how to use it?" / "who uses it?", **only** when that is not obvious.

## Phase 3 — Verification checks

First some steps to prepare for this Phase:
1. clear the context *only* if more than 60%
2. re-read all the code changes from earlier phases

Then verify the following:

- **Spec fidelity**: does the implementation match the behavioral contract from Phase 1?
- **Test quality**: do tests assert meaningful behavior? Never broaden an `assert` to make tests pass.
- **Security surface**: HMAC verification on inbound Shopify webhooks (`ShopifyHmacVerifierService.verifyWebhook`),
  monolith-facing routes mounted inside `authenticate(MONOLITH_WEBHOOK_AUTH)`, HTTPS-only outbound to the monolith in
  production, no secrets logged (tokens, `Authorization` headers, request or response bodies), failures answered
  through `DssError`.
- **Architecture-test compliance**: no reflection, no ad-hoc `Json {}` or `HttpClient(...)` construction (use
  `AppJson`/`MonolithJson`/`ContractJson` and `createSharedHttpClient()`), no wildcard imports, Graphql-generated types only in
  `lib/shopify/` and the allow-listed translation boundaries, package-layer rules respected (see `ArchitectureTest`).
- **Naming conventions**: handler files end with `Handlers.kt`, routing files are `*Routes.kt` (each defining a
  single `Route.*Routes(...)`; `install*` is for plugins), paths in `path/`, Graphql casing (`Graphql`/`Gql`/`gql`, never
  `GraphQL`/`GraphQl`), plural/singular correctness.
- **Traceability**: every new piece can be traced through routing → handler → path → workflow → Graphql operation /
  monolith call → tests.
- **Doc comments**: some functions do not need them, some do: ensure all that do have meaningful doc comments.

Deliver findings in a file named VERIFICATION.md and put that in the "spec folder".
Each such file contains potential issues found, and either:

* one or more suggested solutions (the human developer can choose which suggestion to "leave in")
* point of further research for the human developer to perform or decide on.

At the end of this Phase a human developer should edit the VERIFICATION.md files before proceeding to the next Phase.

## Phase 4 — Implement VERIFICATION suggestions

If the self-review finds issues, the loop back for each issue in a VERIFICATION file, with a clean context, do:

- Spec gap → return to Phase 1.
- Missing or weak test → return to Phase 2 (Red).
- Implementation issue → return to Phase 2 (Refactor).
- Do NOT patch forward or weaken assertions to make things pass.

Each issue that is resolved should be remove from the VERIFICATION file.
Issues that cannot be resolved should remain, including a reason for their lack of resolution.
In the end of this Phase, the human developer should decide if the remain issues need further attention.
