# Expressiveness Audit — the POC model against two real payer forms

**Status:** finding, not a plan. Nothing here proposes work; it records what the model can and cannot
say about forms that already exist.

## Why this document exists

Every other check in this POC ran against fixtures I authored. The plan named the risk plainly:

> **Demo fixtures flatter the model** — hand-authored data unconsciously fits what the model can
> express. Keep a running list of what the model *couldn't* express cleanly — that list is the POC's
> real finding.

This is that list, measured against two production configs supplied from a live environment:

| Form | Steps | Fields | Conditions | Hard stops |
| --- | --- | --- | --- | --- |
| Premera Facility Application | 12 | 57 | 15 (2 step, 13 field) | 1 |
| Highmark "Demo Facility Application" | 8 | 42 | 1 (step) | 1 |

99 fields, 16 conditions, 10 response types, 6 validation keys. A **transformer** — a component that
converts an existing `FormConfig` into the authoring model — is the natural way to prove this
mechanically. It is deliberately **out of scope**; see the last section. This audit was done by
inspection of the real data instead, which answers the expressiveness question without building it.

---

## 1. Verdict by construct

**Expressible today — no model change.** 9 of 10 response types (`text`, `textarea`, `select`,
`radio`, `checkbox`, `email`, `number`, `date`, `file`, `signature`), `required`, `label`, `layout.columns`,
`options`, `accept`, `rows`, `multiple`, all 6 validation keys in use (`maxLength`, `pattern`,
`message`, `maxDate`, `maxSize`, `customValidator`), step `title`/`id`/`type`, `dependsOn`, and
**every flat condition** — 13 of 16 use `equals`, and `contains` is in the grammar precisely because
production has it.

**Expressible, but the artifact keys differ.** The compiler emits the grammar's spelling (`op`,
`eq`) where production reads `operator`/`equals`, and emits `groupFields` where production reads
`groupItem.fields`. Accepted as a POC-level naming difference and recorded in the P2 amendment of the
design doc — with the caveat that the recursion, not the naming, is the real incompatibility.

**Not expressible without a model change — three items, and only these three.**

### 1.1 Answer keys are flat and global — the one finding that revises the design

Production field names are a single global namespace: `npi`, `homeState`, `attestationDate`. The model
emits `stepKey.questionKey`, so importing Premera would rename all 57 of its answer keys and orphan
every in-flight application.

Placement-scoped paths remain right for *new* forms — that was the design review's central finding and
two placements of one section genuinely need disjoint namespaces. But the two facts have to coexist,
which means `QuestionInstance` needs an explicit **answer-key override**: set once when an existing
form is brought in, preserved verbatim thereafter, and left unset for anything authored fresh.

This is the only item here that changes the model rather than the compiler, and it is the reason the
next iteration is scoped as a pair: the override and the transformer land together or not at all. See
[`form-config-poc.md`](./form-config-poc.md) §12 for the shape, the three call sites it touches — the
analyzer's scope being the one that is easy to miss — and a definition of done.

### 1.2 Field-level hard stops with an implicit subject

Both forms carry exactly one, and both have the same shape:

```json
{ "hardStop": { "condition": { "operator": "equals", "value": "no" },
                "message": "You have selected No therefore you do not need to proceed…" } }
```

Note the condition has **no `field`** — the subject is the field the hard stop hangs on. The model has
form-level hard stops only, keyed to an explicit path. Two gaps: the attachment point, and the
implicit subject. Neither is compiled in v0 regardless — `HARD_STOP_NOT_COMPILED` is raised so an
author is told rather than left believing a disqualifying rule fires.

### 1.3 Repeating sub-forms inside a step

Highmark's `additionalPracticeLocations` is a `group` field whose `groupItem` carries 5 sub-fields,
`addLabel`, and `minItems: 0` — "add another practice location". The model expresses repetition at the
**step** level (`Step.Repeating`) and nesting at the **question** level (`Question.children`), but not
a repeating group of fields inside a non-repeating step. One field in 99, and
`GROUPED_QUESTION_NOT_COMPILED` already reports it.

**Deliberately not carried:** `visibilityCondition`. Two Premera fields have it, and nothing in the
renderer reads it — it is dead weight duplicating `condition`. Carrying it would perpetuate a bug.

---

## 2. What production cannot express today — the case for the grammar, from production's own data

The strongest argument for a real condition grammar is not CP-38192. It is that **the shipped configs
already contain rules the renderer cannot evaluate.** `evaluateCondition` has exactly three cases —
`equals`, `notEquals`, `contains` — and then `default: return true`.

| Field | Authored condition | What actually happens |
| --- | --- | --- |
| `providerNetworkAssociateName` | `{"operator":"all","conditions":[…2 clauses…]}` | unrecognised → **always visible** |
| `accreditationCertificateUpload` | `{"operator":"notEmpty"}` | unrecognised → **always visible** |
| `nonAccreditedSurveyUpload` | `{"operator":"equals","value":""}` on a multi-select | an unselected multi-select is not `""` → **never visible** |

The third is the serious one. A non-accredited facility is never asked for its State or Medicare
survey — a compliance document, silently not collected. The first two fail open, the third fails
closed, and none of them fails *loudly*.

Two further signals that authors are already writing past the renderer: the `visibilityCondition`
keys nothing reads, and a sibling CalMHSA config whose `_metadata.notes` asks a future implementer to
"implement `operator` `all` with a `conditions` array, and `notEmpty` on select values".

Premera's compound rule is expressible in the POC grammar exactly as authored:

```json
{ "all": [ {"field":"…alreadyContractedWithPremera","op":"eq","value":"no"},
           {"field":"…providerNetworkApproval","op":"eq","value":"yes"} ] }
```

## 3. What normalization is actually worth, measured

The catalog and option-set thesis, checked against real data rather than asserted:

**Option sets pay off clearly.** 14 distinct option lists across the two forms, of which 3 are
duplicated — but the duplication is concentrated where it matters:

| List | Inline copies | After normalization |
| --- | --- | --- |
| yes / no | **14** | 1 |
| US states (54 entries) | 2 | 1 |
| PCP / Specialist | 2 | 1 |

18 inline copies collapse to 3 definitions. The 54-entry state list being pasted twice per form is
exactly the maintenance cost the design predicted.

**Cross-form question reuse is weaker than assumed, and this is a real finding.** Only **2** of ~99
field names appear in both forms: `attestationDate` and `npi`. Two facility applications from
different payers share almost nothing at the question level.

That argues the catalog's value is narrower than "reuse questions across payers": it is mostly
*aliasing* and *duplicate prevention*, not composition. And the one shared question proves the point —
`npi` is labelled "NPI" in one form and "NPI Number" in the other. Same question, two payer phrasings.
That is precisely the case `Question.aliases` and `DuplicateDetector` exist for, and it showed up in
the first two real forms examined.

## 4. The transformer — named, and out of scope

What §11 of the design doc called the "seed importer" is better named a **transformer**: the inverse of
the compiler, converting an existing `FormConfig` into catalog questions, option sets, section
definitions and a form definition.

Scoped out of v0 by decision, and scoped *in* as the next iteration —
[`form-config-poc.md`](./form-config-poc.md) §12, paired with the answer-key override of §1.1.
Recorded here so the next reader knows what it would have to do:

- One section definition per step (production has no section concept; reuse appears later, when an
  author extracts a template)
- Each distinct field `name` → one catalog question, with the original name kept as the answer-key
  override from §1.1, and differing payer phrasings absorbed as `labelOverride` or `aliases`
- Each distinct option list → one option set, deduplicated by value set
- `dataSource.data[].filterValue` + `dependsOn` → `filteredBy` plus tagged option values
- Flat conditions → grammar leaves; `{operator:"all", conditions:[…]}` → `all`; `notEmpty` → `exists`

With it, the round-trip gate becomes mechanical: transform → compile → compare against the original
using `ArtifactComparison`, which is built and calibrated (16 tests, verified to fail on both
too-strict and too-lax comparisons). Without it, §1 above is the answer, established by inspection.
