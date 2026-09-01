# Form Config POC — Execution Plan

## Context

Payer form logic lives in frontend code today. `apps/provider-portal/components/common/dynamic-form-renderer/DynamicFormRenderer.tsx` is **3,991 lines in one file**, and changing a rule — *"hide the DEA section when specialty is one of these five"* — needs an engineer, a Jira ticket and a release. CP-38192 is blocked on exactly that, with two client forms waiting; three Florida Blue sections currently ship unconditioned, which is functionally wrong.

This POC tests one bet: **move form definition out of code and into configuration.** A catalog of reusable questions composes into sections, sections into forms, and a compiler emits the *existing* runtime artifact so nothing downstream changes.

It doubles as a proposal to rewrite the service under **Domain-Driven Design on MongoDB**, ahead of the DAL migration.

Design is settled in [`form-config-poc.md`](./form-config-poc.md) (1,107 lines), already amended after a staff design review. **This plan is execution only** — it does not re-open design decisions.

**What the POC must prove:**
1. The model expresses real payer forms without per-form code.
2. CP-38192's compound AND / OR / `in` rules are pure config.
3. One expression grammar, two implementations, provably in agreement.

---

## Folder structure

```
/Users/akshay.prakash/code/certifyos/
├── form_poc/
│   ├── README.md
│   ├── form_poc_backend/                 Quarkus 3.23 · Java 21 · MongoDB
│   │   ├── build.gradle, settings.gradle, gradle.properties, Makefile
│   │   └── src/main/java/com/certifyos/forms/
│   │       ├── question_catalog/{domain,application,infrastructure,interfaces}
│   │       ├── form_authoring/{domain,application,infrastructure,interfaces}
│   │       └── shared_kernel/{expression,exception,security}
│   └── form_poc_shared/                  language-neutral contract
│       ├── grammar/expression.schema.json
│       ├── conformance/*.json            the FE↔BE contract
│       └── fixtures/                     seed catalog, sections, one form
│
└── certifyos-frontend/                   (existing monorepo)
    ├── apps/form-studio/                 the POC UI
    └── packages/form-expression/         TS evaluator + operator registry
```

> **On `form_poc_frontend`:** you asked for it as a sibling folder but then chose `apps/form-studio` in the monorepo, so the UI can reuse `@repo/design-system` and type-check compiler output against the real `FormConfig`. Those are mutually exclusive, so `form_poc_frontend` does not exist. `form_poc_shared/` holds what genuinely crosses both halves; the backend reads conformance fixtures from a relative path and `packages/form-expression` gets a copy-in script, so the monorepo keeps no path dependency outside itself.

---

## Phase 0 — Scaffold

### Backend

**Write a minimal build from scratch — do not copy and strip provider-portal-api's.** That file carries ~200 lines of production concerns (GCP private Maven, OpenAPI codegen, Cloud Run deploy, four quality tools) and stripping it is slower to write *and* slower to read than starting clean.

- `build.gradle` (~40 lines): Quarkus **3.23.0**, Gradle **8.13**, Java **21**, group `com.certifyos.forms`
- Dependencies — eight, total:
  - `quarkus-arc`, `quarkus-rest`, `quarkus-rest-jackson`
  - `quarkus-mongodb-panache` (infrastructure layer only)
  - `quarkus-hibernate-validator` (`@Valid` on request DTOs)
  - `quarkus-smallrye-openapi` — Swagger UI is how the API gets poked by hand before the FE lands
  - test: `quarkus-junit5`, `mockito-core`, `archunit-junit5`
- **Spotless** with `palantirJavaFormat` — one line, and it means reviewers read Certify-shaped code
- **`Makefile`** — five targets: `dev`, `test`, `check`, `format`, `build`
- Single `application.properties`: config prefix `form-poc.<area>.<key>`, port **9100**, plus a `%test` block. No `.sample.env`, no `%local` / `%prod` — there is no deploy and there are no secrets
- **MongoDB via Quarkus Dev Services** — leave the connection string unset in dev and Quarkus starts the container itself. **No `docker-compose.yml`**
- `%test` uses in-memory repository fakes, so `make test` needs no Docker at all

**Deliberately omitted, because this code gets deleted:** SpotBugs, Checkstyle, Jacoco, git hooks, `quarkus-smallrye-health` (nothing orchestrates it), Lombok (Java 21 records cover it), and the ~20 GCP/Docker/Cloud-Run Makefile targets.

**ArchUnit is the one quality tool kept**, because the POC's second thesis is *"DDD layering is workable here"* — ArchUnit proves that claim rather than asserting it, and it is a single test file.

### Frontend

- `apps/form-studio/` — copy the shape of `apps/provider-portal` (the leaner of the two apps)
  - `package.json` (name `form-studio`, `next dev --port 3200`), `tsconfig.json`, `next.config.js`, `eslint.config.js`, `next-env.d.ts`
  - `pages/_app.tsx`, `pages/index.tsx`; `providers/{theme,query}-provider.tsx`; `lib/query-client.ts`; `services/api-client.ts`; `constants/env-variables.ts`; `styles/global.css`
- `tsconfig.json` extends `@repo/typescript-config/nextjs.json` with `"@repo/design-system/*": ["../../packages/design-system/*"]` plus self-aliases per top-level folder
- `next.config.js` — phase function; **`transpilePackages: ["@repo/design-system", "@mui/x-tree-view", "@mui/x-data-grid"]`** (the design system ships untranspiled; omitting it fails at build)
- `providers/theme-provider.tsx` — copy `apps/provider-portal/providers/theme-provider.tsx` verbatim
- `_app.tsx` nesting: `QueryClientProvider` → `ThemeProvider` → `<CssReset />` + `<Toaster />` → page. Call `setupThirdPartyLicenses` at module scope (required before any MUI X Pro component renders)
- `eslint.config.js` — carry the two mandatory rules: `no-restricted-syntax` banning `process.env` outside `constants/env-variables.ts`, and `no-restricted-imports` banning `@mui/material` in favour of `@repo/design-system`
- **Two explicit allowlists to edit by hand** (`apps/*` is glob-registered, but these are not):
  - root `package.json` → add `--filter=form-studio` to `test`, and a `dev:form-studio` alias
  - `turbo.json` → `build.env` allowlist, only if a new build-time env var is introduced
- Skip: Auth0, Sentry, Flagsmith, i18n, `middleware.ts`, `entrypoint.sh`, Dockerfile — all out of scope

### Shared

- `form_poc_shared/grammar/expression.schema.json` — JSON Schema for the grammar
- `form_poc_shared/conformance/{operators,combinators,quantifiers,refs,context}.json` — `{ expr, context, expected }` triples
- `form_poc_shared/fixtures/` — catalog (~35 questions), option sets, 8 section definitions, one form definition. **Content drawn from the real Florida Blue Recred form and CP-38192's documented rules**, not invented
- `packages/form-expression/` — new workspace package; `scripts/sync-fixtures.mjs` copies `form_poc_shared/conformance` in

---

## Phase 1 — Expression grammar (both sides)

The highest-value code in the POC. Everything else depends on it, and it is the one place FE and BE must agree.

### Backend — `shared_kernel/expression`

- `Expression` — sealed interface; records `All`, `Any`, `Not`, `Ref`, `Some`, `Every`, `Leaf`
- `ExpressionParser` — JSON → `Expression`, rejecting unknown operators and malformed nodes with a typed error
- `Operator` — enum + registry: `eq, neq, in, nin, gt, gte, lt, lte, exists, empty, matches`, each carrying `arity` (`none|single|list`) and applicable response types
- `EvaluationContext` — `answers` (placement-scoped paths) + `viewer` + `entity` + `tenant`. Not answers alone: production already gates steps by viewer role via `audience`
- `ExpressionEvaluator` — `all`/`any` combine expressions; `some`/`every` quantify over repeat items with `@item.` resolution; `ref` resolves a named condition
- `ExpressionAnalyzer` — static analysis for the compiler: referenced paths, cycles, unresolved refs, values outside their option set, forward references

### Frontend — `packages/form-expression`

- Mirror of the same grammar in TypeScript: `types.ts`, `evaluate.ts`, `describe.ts`, `registry.ts`
- **`registry.ts` is the single source that drives the builder UI** — operator label, `arity` (which value input renders), and `appliesTo` (which operators appear for a given response type). Adding an operator must be one entry plus one function, with zero UI change
- `describe.ts` — prose generation, with two rules from the design review:
  - **Show / Hide verb, never an exposed NOT.** `Hide × all/any` covers both De Morgan cases
  - **Resolve labels through the option set.** `DC` means different things in the provider-type and specialty sets; printing stored values is ambiguous
- Both implementations run the **same conformance fixtures** in CI. That suite is the contract, not shared code

> FE work can start as soon as this package exists — Phases 2–4 (backend) and Phase 5 (frontend) run in parallel.

---

## Phase 2 — Backend domain and persistence

### Domain

- **`question_catalog`** — aggregates `Question`, `OptionSet`; value objects `QuestionId`, `ResponseType`, `ValidationRule`, `Alias`, `CatalogStatus` (`proposed → active → deprecated`); domain service `DuplicateDetector` (fuzzy match on label + aliases — the guard against catalog rot, risk #1 in the design doc)
- **`form_authoring`**
  - `domain/definition/` — `SectionDefinition`, `QuestionInstance`, `Placement`, `PlacementKey`, `Origin`, `DriftCalculator`
  - `domain/publishing/` — `FormVersion` (immutable), `CompiledForm`, `ChangeSet`, `ChangeClass`
  - `domain/compile/` — `FormCompiler`, `CompilationReport`
  - `domain/port/` — repository interfaces + `QuestionCatalogPort`

**Invariants belong in aggregates, not services:**
- `PlacementKey` unique within a form; immutable once published
- `FormVersion` immutable after construction
- `SectionDefinition.externalRefs` computed on save, never authored
- A template-sourced question is disabled, never deleted — provenance survives

### Persistence

- `*Document` types in `infrastructure/mongo`, separate from domain, with explicit mappers. **No Panache or BSON annotations on domain objects** — this is what keeps the DDD proposal honest
- Repositories per aggregate root; `FormAuthoringReadModel` for CQRS-lite list/search/history projections that bypass aggregate hydration
- Collections and indexes exactly as `docs/form-config-poc.md` §5.9
- **Active version is derived, not flagged** — highest version with `publishedAt` set. One insert per publish, no multi-document write, no transaction, and versions stay genuinely immutable
- `@RunOnVirtualThread` on the application layer with blocking Mongo calls. **No Mutiny** — readable domain code is part of what's being proposed
- Seed loader reading `form_poc_shared/fixtures/` on `%dev` startup

---

## Phase 3 — Compile and publish

- `FormCompiler.compile(definition, catalogSnapshot) → CompiledForm` — pure function, no I/O
  - Resolve catalog references, apply instance overrides
  - Drop `enabled: false` questions and placements entirely
  - Inline named conditions (frozen at compile, per P3)
  - **Each placement emits exactly one `step`**; `visibleWhen` maps onto the step's `condition`
  - Emit the **existing** `FormConfig` shape — `steps` / `fields` / `name` / `type`, plus `dependsOn` + `filterValue` for option filtering
  - Run `ExpressionAnalyzer`; on failure throw `CompilationFailed` carrying a structured `CompilationReport`
- `ChangeSet.between(previous, next)` → `ChangeClass` (`text` | `additive` | `structural`) + `changedKeys`. Retires the hand-ticked `isTextOnlyUpdate` checkbox that currently decides whether every in-progress answer gets wiped
- `PublishFormVersionHandler` — load, resolve catalog, compile, diff, construct `FormVersion`, save, emit `FormVersionPublished { formVersionId, formId, changeClass, changedKeys }` via CDI. **No subscriber in v0** — `form_response` is out of scope; the event exists so the dangerous operation stays behind a boundary
- `PreviewChangeSetHandler` — compile + diff without persisting

---

## Phase 4 — Backend API

Endpoints per `docs/form-config-poc.md` §9. Controllers are thin: one application-service call each, primitive DTOs in, view records out, **no try/catch**.

- `QuestionCatalogResource` — search, propose, promote (dedupe-checked), deprecate, usage
- `OptionSetResource` — list, upsert
- `SectionDefinitionResource` — CRUD, add/disable question, drift, promote-to-template
- `FormDefinitionResource` — create-from-blueprint, place/patch placement, validate, change-preview, publish, versions, compiled artifact
- `BlueprintResource`, `SectionTemplateResource` — read-only in v0
- `DomainExceptionMapper` over a **sealed** `DomainException` hierarchy (`NotFound` 404, `ConflictingState` 409, `InvariantViolated` 422, `CompilationFailed` 422 + report). Sealed gives an exhaustive switch — an unhandled new exception won't compile
- Auth stubbed: `UserContext` producer returning a fixed actor; tenant from the path
- Follow the house resource style: `public final class`, constructor `@Inject`, `@Tag`, `@Operation(operationId=…)`, `@APIResponse`

---

## Phase 5 — Frontend screens

Feature folders under `apps/form-studio/features/` following the `apps/web` convention (`components/`, `hooks/{queries,mutations}/`, `services/`, `types/`, `constants/`, `templates/`).

**API strategy:** there is **no MSW anywhere in the monorepo**. Rather than introduce it, use the established house idiom — a `services/` layer whose functions read from typed fixture modules (as `features/practitioner-monitoring/mocks/` does for unshipped APIs), swapped to real `apiClient` calls behind one flag once Phase 4 lands.

- **`features/catalog/`** — question list (`data-grid`), detail panel with validations / platform mapping / aliases / usage, proposed-vs-active states
- **`features/option-sets/`** — value list with tags
- **`features/sections/`** — section list with computed `Requires` column and drift indicator; section editor with add / disable (strikethrough, not removal) / reorder
- **`features/forms/`** — the main surface
  - `FormTree` — the spine, built on `@repo/design-system/components/sidebar-tree-view`; renders **structure**, **diff**, and error states from one component
  - `FormDiagram` — inline SVG; Form → Steps with dashed logic edges, expandable to question level
  - `ConditionBuilder` — **generated from the operator registry**; Show/Hide verb, flat ALL/ANY rows, named-condition rows, value pickers sourced from the option set, field picker restricted to earlier placements, JSON escape hatch
  - `ChangePreview` — change class, changed keys, impact statement
  - `CompilationReport` — errors pinned to tree nodes, plain language (not `DANGLING_REF`)
- **`features/fill-preview/`** — the provider-facing renderer matching the live portal: serif titles, connected section-nav cards with status icons, progress bar, numbered questions, grouped repeat cards, red required states, gold Next. Plus a **logic trace** panel showing why each step is shown or hidden

The published prototype (`https://claude.ai/code/artifact/c145d9d6-f152-431a-a278-1b276698ff34`) is the reference for all of these — layout, interaction and copy are already resolved there.

---

## Testing

Chosen depth: **unit + compiler golden tests, no Docker.**

### Backend — `src/test/java/...` mirroring main

House convention (confirmed): plain JUnit 5 + `@ExtendWith(MockitoExtension.class)`, constructor injection, bare `org.junit.jupiter.api.Assertions`, class-level Javadoc explaining *why* the test exists. No AssertJ on the classpath.

**Expression conformance** — driven by `form_poc_shared/conformance/*.json`, `@ParameterizedTest` over every case:
- Each operator: match / non-match / null input / wrong-type input
- `all`: T,T→T · T,F→F · empty→T
- `any`: F,F→F · T,F→T · empty→F
- `not`: inverts; double negation
- Nested combinators three deep
- `some` over empty array → false; `every` over empty → true
- `some` with one match; `every` with one failure; `@item.` resolution inside a repeat scope
- `ref` resolves; `ref` inside `not`; unresolved `ref` → analyzer error
- Context resolution: `viewer.role`, `entity.npi`, `tenant.*`

**Compiler golden tests** — definition JSON in, compiled artifact out, compared to a checked-in expected file:
- One placement → exactly one step
- `enabled: false` question is absent from the artifact entirely
- Named condition is inlined, not left as a `ref`
- Option filtering emits `dependsOn` + `filterValue`
- Placement-scoped answer keys survive into the artifact
- Two placements from the same section definition produce two independent key namespaces

**Analyzer — negative cases (each must fail compilation with the right code):**
- Condition references a question not present in the form
- Condition references an unresolved named condition
- Condition value not a member of its option set
- Forward reference — condition names a question in a *later* placement
- Cycle — A shows B, B shows A
- Duplicate `placementKey` within a form
- Unreachable placement — contradictory conditions (`providerType` both `MD` and `DC`)

**ChangeSet classification:**
- Label-only edit → `text`, `changedKeys` empty
- New optional question → `additive`
- New required question → `structural`
- Condition added to an existing step → `structural`
- `placementKey` renamed → `structural`
- `changedKeys` lists only genuinely changed questions, not every question in a touched step

**Aggregate invariants:**
- `FormDefinition` rejects a duplicate `placementKey`
- `FormDefinition` rejects a `placementKey` change after publish
- `FormVersion` cannot be mutated after construction
- `SectionDefinition` computes `externalRefs` on save
- Disabling a template question preserves `origin: template`

**Catalog:**
- `DuplicateDetector` flags a near-duplicate label ("NPI Number" vs "NPI")
- `DuplicateDetector` flags a match against an existing alias
- Promote is blocked when a duplicate is found
- Promote succeeds and flips `proposed → active`

**Repositories** — against in-memory fakes: save/find round-trip per aggregate; active version resolves to the highest with `publishedAt`.

**ArchUnit:** domain imports nothing from `application` / `infrastructure` / `interfaces`; no cross-context imports except through a port; no Panache or BSON types referenced from `domain`.

### Frontend

Copy `apps/web/vitest.unit.config.ts` and `vitest.component.config.ts` plus `test-utils/` (adapting `TestWrapper` — QueryClient + ThemeProvider only, no i18n).

**Unit (`*.test.ts`, node):**
- **The same `form_poc_shared/conformance` fixtures the backend runs** — this is the FE↔BE contract, and it is the single most important test in the POC
- `describe()` prose: Show verb, Hide verb, labels resolved through the option set (`DC` → `Chiropractic`), `ref` renders its named label, list truncation ("+2 more")
- Operator registry: `appliesTo` filters correctly per response type; `arity` selects the right value-input shape
- Answer-path helpers: build, split, owning-placement lookup

**Component (`*.component.test.tsx`, chromium):**
- `ConditionBuilder` — operator dropdown reflects the selected field's response type; switching Show→Hide produces `{not: …}`; field picker excludes questions in later placements; list-arity operator renders a multi-value input
- `FormTree` — renders steps in order; condition line appears only on conditioned steps; disabled question renders struck-through; error badge pins to the offending node
- `FormFiller` — a hidden step is absent from the nav; answering a gating question makes a step appear; Next with an empty required field holds the user and shows the red state; progress percentage tracks visible required questions only
- `ChangePreview` — renders change class and the changed-key list
- `FormDiagram` — renders one node per step; a logic edge exists for each cross-step dependency

**Commands:** `pnpm test --filter=form-studio`, `pnpm test:unit`, `pnpm test:component`. Root `package.json` `test` script must be extended with `--filter=form-studio` or the app is invisible to CI.

---

## Verification

### Local run

```bash
# backend — needs a local mongod, NOT Docker (see below)
brew services start mongodb-community
cd form_poc/form_poc_backend
make dev                      # :9100, seeds fixtures from form_poc_shared/fixtures on %dev

# frontend
cd certifyos-frontend
pnpm install
pnpm dev --filter=form-studio # :3200 — reads NEXT_PUBLIC_API_BASE_URL, defaults to :9100
```

**Dev Services is off, and not because Docker is missing.** The original plan used Quarkus Dev
Services so nothing had to be installed. It does not work on Docker Engine 29: the engine sets
`MinAPIVersion 1.40`, while the testcontainers on Quarkus 3.23's classpath (1.21.0) probes the daemon
at API v1.32. `/info` answers HTTP 400 with an empty engine payload, and testcontainers reports
"Could not find a valid Docker environment" — with a reachable socket and a working `docker` CLI.
`DOCKER_API_VERSION` does not help; docker-java does not consult it for that probe. So the symptom
looks exactly like a broken Docker install and is not one. Set
`QUARKUS_MONGODB_DEVSERVICES_ENABLED=true` and clear the connection string to retry after a
testcontainers bump.

### Automated gates

```bash
cd form_poc/form_poc_backend && make check    # spotless + checkstyle + test + gradlew check
cd certifyos-frontend && make validate        # lint + check-types + test
```

### Manual demo script — the acceptance walk

1. **Catalog** — ~35 questions; open NPI, see aliases and `used in N forms`
2. **Forms** → *Florida Blue Recred* → **Structure**; select **DEA Registration**
3. Condition reads **`Hide when Specialty exempt from DEA requirements`** — a named condition, not a copied list
4. Toggle **Diagram**; expand *Applicant Details*; `providerType` carries a `gates 2` badge, a dotted edge filters `specialty`
5. **Fill preview** — set specialty to **Chiropractic**; DEA Registration disappears from the nav. Open **logic trace**: `specialty(DC) ∈ [...] → true` inside `NOT(...)`
6. Answer *Is billing address the same as primary?* → **Yes**; Billing Address disappears (the compound AND)
7. Enter a practice address **and** a billing address; confirm both persist — `practiceLocation.line1` and `billingAddress.line1` are independent
8. **Changes** — `structural`, changed keys listed, impact stated
9. **Publish** — a new version appears; open the compiled artifact and confirm **one step per placement**

### Success criteria

- ✅ CP-38192's NSCP compound-AND and Florida Blue `not`+`in` rules expressed as config
  - ⚠️ The "**zero rendering-engine changes**" half of this criterion was wrong when written. A recursive `all`/`any`/`not` cannot be expressed in production's flat `{field, operator, value}` condition slot, and that recursion is what CP-38192 needs. The compiler emits the existing artifact *shape*; the condition slot is a breaking change. See §3 P2 of the design doc.
- ✅ Both expression implementations pass the identical conformance suite
- ✅ Compiler emits valid `FormConfig` — one placement, one step
- ✅ Change classification is computed, and `changedKeys` is precise
- ✅ Two placements of one section definition keep independent answers

**Deferred by decision, not oversight:** the seed importer and therefore the round-trip gate (§10/§11 of the design doc); repeating-answer capture, so `some`/`every` is grammar-complete but not exercised end-to-end; `form_response`, submission integration, PDF ingestion, named scenarios, and the dependency/impact view.

---

## Risks

| Risk | Mitigation |
|---|---|
| **Catalog rot** — near-duplicate questions accumulate and reuse collapses. Risk #1 in the design doc | `proposed → active` staging, `DuplicateDetector` on promote, aliases as the landing place for payer phrasing |
| **The two evaluators drift** | Shared conformance fixtures run in both CI pipelines; treat a fixture change as a contract change |
| **Mongo is new ground** — no dependency, config, or container setup exists anywhere in the workspace | Isolated to `infrastructure/`; `%test` uses in-memory fakes so the whole suite runs without Docker |
| **Demo fixtures flatter the model** — hand-authored data unconsciously fits what the model can express | Draw fixture content from the real Florida Blue form and CP-38192's documented rules; keep a running list of what the model *couldn't* express cleanly — that list is the POC's real finding |
