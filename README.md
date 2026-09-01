# Form Config POC

A throwaway proof of concept. **Nothing here ships** — the code gets discarded; only the decisions survive.

## What it tests

Payer form logic lives in frontend code today. `DynamicFormRenderer.tsx` is 3,991 lines in one file, and changing a rule — *"hide the DEA section when specialty is one of these five"* — needs an engineer, a ticket and a release. CP-38192 is blocked on exactly that.

This POC tests whether form definition can move **out of code and into configuration**: a catalog of reusable questions composes into sections, sections into forms, and a compiler emits the runtime artifact.

It doubles as a proposal to rewrite the service under **Domain-Driven Design on MongoDB**.

**What it must prove**

1. The model expresses real payer forms without per-form code.
2. CP-38192's compound AND / OR / `in` rules are pure config.
3. One expression grammar, two implementations, provably in agreement.
4. A form for a payer nobody has onboarded before can be **assembled from nothing** — catalog to
   published artifact, with no fixture in the path.

All four are demonstrated. #4 was added late: for most of the build the API had no way to place a
section into a form, so every form had to be instantiated from a blueprint and every blueprint was a
hand-written fixture. Nothing failed, because the seed data always had a form ready to edit — which
is the general warning, and it is recorded in the note at the end of §9 of the design doc.

One caveat on #2 that the design doc states plainly and this README will not bury: the compiler emits the existing artifact *shape*, but the condition slot is **not** backward compatible — a recursive `all`/`any`/`not` cannot be expressed in production's flat `{field, operator, value}`, which is the very thing CP-38192 is about. See §3 P2 of the design doc.

## Layout

```
form_poc/
├── form_poc_backend/     Quarkus 3.23 · Java 21 · MongoDB
└── form_poc_shared/      language-neutral contract
    ├── grammar/          JSON Schema for the condition grammar
    ├── conformance/      84 cases — the FE↔BE contract
    └── fixtures/         catalog, option sets, section templates,
                          blueprints, sections, one form
```

**The UI is not in this repository.** It lives in the frontend monorepo so it can reuse `@repo/design-system` and type-check compiler output against the real `FormConfig` type:

| Where | What |
| --- | --- |
| `certifyos-frontend/apps/form-studio` | the studio itself |
| `certifyos-frontend/packages/form-expression` | the TypeScript half of the grammar |
| `certifyos-frontend/packages/form-studio-tests` | Playwright verification against a live stack |

Test counts: **571** backend, **130** studio, **85** in the grammar package, **46** browser specs.

## Running it

Needs a local MongoDB. **Quarkus Dev Services is deliberately off** — see the comment in
`application.properties` for why, but briefly: Docker Engine 29 sets `MinAPIVersion 1.40` while the
testcontainers on Quarkus 3.23's classpath probes at v1.32, so Dev Services reports "could not find a
valid Docker environment" with a perfectly healthy Docker install. It looks like a broken machine and
is not one.

```bash
brew services start mongodb-community    # :27017

cd form_poc_backend
make dev        # :9100, seeds fixtures on startup
make test       # 571 tests, no Mongo and no Docker needed
make check      # format check + tests + ArchUnit
```

Swagger UI at http://localhost:9100/swagger-ui. Override the database with `MONGO_URI`.

Seeding only populates an **empty** database, so studio edits survive a restart. To reset:
`mongosh --eval 'db.getSiblingDB("form_poc").dropDatabase()'` and restart.

## The conformance suite

`form_poc_shared/conformance/` holds 84 `{ expr, context, expected }` cases across operators (36),
combinators (18), quantifiers (12), non-answer context (10) and named conditions (8).

Both suites report 85 tests: the 84 cases plus one guard asserting the fixture set actually loaded,
because an empty set would otherwise pass vacuously.

**Both evaluators run these identical fixtures.** The frontend must evaluate expressions for instant
reveal, and the backend must evaluate them to be the authority — so two implementations are
unavoidable. The fixtures are the contract; treat a change to them as a change to the contract.

## What is not committed

`form_poc_shared/real-forms/` — two production payer configs (Premera, Highmark) pulled from a live
environment, used to audit whether the model can express forms that already exist. They are real
client configurations and stay off the repository; nothing in the build reads them.

The findings they produced are recorded in full in the expressiveness audit, including the three
constructs the model cannot express and the measured normalisation payoff.

## Design and findings

All four live in [`docs/`](./docs). They were originally written into `provider-portal-api/docs/`,
which was wrong twice over: that is a production service, and these describe a throwaway POC. They
were never committed there, so moving them cost nothing — but the mistake is worth naming, because a
design document filed against the wrong service is a document the next person will not find.

| Document | What it covers |
| --- | --- |
| [`docs/form-config-poc-findings.md`](./docs/form-config-poc-findings.md) | **start here** — the recommendation, what was proven, and what argues against it |
| [`docs/form-config-poc.md`](./docs/form-config-poc.md) | the model, the grammar, the DDD decomposition, and §12 the next iteration |
| [`docs/form-config-poc-expressiveness-audit.md`](./docs/form-config-poc-expressiveness-audit.md) | the model measured against two real payer forms |
| [`docs/form-config-poc-execution-plan.md`](./docs/form-config-poc-execution-plan.md) | build order and test plan, with its own wrong prediction recorded |

## Deliberately omitted

SpotBugs, Checkstyle, Jacoco, git hooks, health checks, Lombok, deploy tooling. This code gets
deleted — the quality bar is "does it prove the thesis", not "is it production-ready". ArchUnit is
the one exception, because "DDD layering is workable here" is a claim the POC has to demonstrate
rather than assert.

Sections and forms accumulate in the dev database as you use the studio and the browser suite, and
there is no delete endpoint for either — see `packages/form-studio-tests/README.md` for why the specs
key everything to the run. To start clean:
`mongosh --eval 'db.getSiblingDB("form_poc").dropDatabase()'` and restart the backend.

The studio carries its own reference for authors at `/docs/rules`, and a **Rules** screen at `/rules`
listing every condition across every form — including the steps that have none, which is how the
production defect this design targets becomes visible. The operator table on the docs page is
generated from the operator registry and its worked examples are evaluated live by the same grammar,
so neither can drift from the implementation.

Three features are stored by the model and **not** compiled: hard stops, grouped questions, and a
step's audience rule. Each raises a compilation notice rather than vanishing, so an author is told
instead of believing a disqualifying rule works.
