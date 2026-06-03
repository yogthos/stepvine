# Architecture Refactor Plan

## Status (branch `arch-refactor`)

**Done & verified** (217 tests + clean storyboard after each):
- ✅ Phase 0 — dead code removed (−236 LOC net): `web/routes/utils.clj` +
  `editor/actions.cljc` deleted; `editor.cljc` rewritten (5 dead fns, cruft, dead
  requires gone); `apply-changes!`/`get-fields`/`snapshot-of` removed.
- ✅ Phase 1 (partial) — triplicate `rerender-collection!` collapsed to one shared fn.
- ✅ Phase 2 (partial) — `:fmt` formatting extracted from `render.clj` → `format.clj`.
- ✅ Phase 3 — `workflow.clj` → `workflow_rules.clj` (the worst name collision).
- ✅ Phase 4 — documentation: **ARCHITECTURE.md** (request flow + invariants + feature
  map), `forms/README.md`, `components/README.md` (widget catalog), inline `:meta`
  schema (`documents.clj`) + table view-state shape (`session.clj`).

**Deferred** (documented here; not done — judged high-churn or tangled for a late
single-session pass; safe to pick up incrementally next):
- ⏳ `render.clj` signals + endpoints extraction — ~50 widget/caller files use these
  as the rendering SPI; high mechanical churn, do as a dedicated pass.
- ⏳ `session.clj` table view-state → `view_state.clj` — shares `update-view!`/
  `item-keys` with `move-item!`/`clear-items!`; unpick those couplings first.
- ⏳ `cells/document.clj` landing HTML → `web/views/landing.clj`.
- ⏳ `forms.clj` `prepare-form` → `forms_compile.clj` (store shouldn't compile).
- ⏳ `table.clj` inline JS blobs → a served `/vendor` asset.
- ⏳ remaining dedups: `load-edn-dir`, `opt-value/opt-label`, `field-bind-attrs`,
  `read-signals`, `append-meta!`.
- ⏳ store protocol parity (`users`/`access`/`audit`) + shared `store/sqlite`.

---


Synthesis of a chiasmus structural analysis (low community cohesion 0.04–0.13;
`render.clj`/`session.clj` functions scattered across 6+ clusters = overloaded
modules) + four parallel review agents (dead code, duplication, architecture,
feature-organization/LLM-maintainability). Goal: a codebase where any feature's
code is easy to find, understand, and change safely — optimized for LLM + human
maintainers.

Branch: `arch-refactor`. Every phase is test-verified (`clojure -M:test` + a clean
storyboard run) and committed separately. No behaviour changes — pure structure.

---

## Phase 0 — Dead code removal (pure deletion, safest)
- `web/routes/utils.clj` — whole namespace unreferenced (`route-data*`). Delete.
- `editor.cljc` — 5 never-wired public fns (`create-session!`, `connect!`,
  `lock-and-save-ids!`, `mark-action-pending!`, `clear-action-pending!`) + their 3
  private helpers; dead requires (`sci`, `pprint`, `clojure.set/difference`); stale
  TODO/NOTES blocks.
- `editor/impl.cljc` `apply-changes!` (superseded by `apply-changes`, 0 callers).
- `editor/data.cljc` `get-fields`; `session.clj` `snapshot-of` (0 callers).
- `validation.clj` unused `[clojure.string :as str]`; `editor/locks.cljc` commented
  `println` debug.
- (Verified: NO dead widgets — every `render-widget` method is used by a demo form
  or test.)

## Phase 1 — De-duplication: single sources of truth
1. `opt-value`/`opt-label`/`opt-pair` (5+ widgets + search + sources) → one home.
2. `read-signals` (`json/read-value (d*/get-signals req)`) — 7 sites, inconsistent
   try/catch → one helper.
3. `rerender-collection!` — 3 near-identical copies (cells/form, web/collection_entry,
   web/collection_item) → one shared fn.
4. EDN dir loaders (`edn-file?` + `load-dir` in forms/options/partials/terminology) →
   `load-edn-dir`.
5. `field-bind-attrs` (post/lock/unlock attr trio) — ~9 input widgets → one helper.
6. `append-meta!` (`append-report!` vs `record-effect!` both read-conj-into-:meta) →
   one helper in `documents`.

## Phase 2 — Split overloaded modules (clear responsibilities)
- `render.clj` (458) → extract:
  - `signals.clj` — `signal-name`/`$`/`item-signal-name`/signal maps (the Datastar
    wire vocabulary, currently imported by session/sse/cells just for this).
  - `endpoints.clj` — `field-post-url`/lock/unlock/`coll-item-url` (URL scheme; the
    renderer shouldn't own routes; table.clj re-derives them).
  - `format.clj` — `fmt-spec`/`fmt-value`/`fmt-text-expr` (`:fmt` printf).
  - render.clj keeps the markup walk + `render-widget` dispatch + collection
    projection.
- `session.clj` (372) → extract table view-state (sort/filter/page/columns) to
  `view_state.clj` — flagged in-source as "not document data".
- `cells/document.clj` (453) → move landing/index hiccup (`landing-html`, `doc-row`,
  `index-page-html`, inline CSS) to `web/views/landing.clj`; cell becomes
  parse→orchestrate→view (matching `cells/form.clj`).
- `forms.clj` — move `prepare-form`/`compile-import-effects` to `forms_compile.clj`
  so the store returns raw EDN (stop the store depending "up" on cascades/imports/
  validation).
- `table.clj` (623) → move the 3 inline JS blobs to a served `/vendor` asset;
  move `apply-view`/`apply-column-overlay` to the view-state ns.

## Phase 3 — Naming disambiguation
- `workflow.clj` (pure rules) collides with `workflows/workflow.clj` (FSM) and
  `cells/workflow.clj`. Rename → `workflow_rules.clj` (`…workflow-rules`).
- Audit web-handler naming (`*-handler` for raw ring fns; `*-page` for full-page
  GETs) for consistency.

## Phase 4 — Documentation & signposts (biggest LLM-maintainability lever)
- `ARCHITECTURE.md` — the request-flow map (route → workflow def → cell pipeline →
  session → engine(domino) → hub(SSE)), naming the keyword-indirection seam and
  noting that **broadcast happens in `session/swap-session!`'s on-update hook**.
- Document the hidden invariants (each currently a trap):
  - `:meta` map schema (keys + writer + reader) on `documents`.
  - `signal-name` sanitization contract (render↔read must agree) + collision check.
  - `$rev`: deliberately top-level (not `:meta`); seed↔bump↔check links → a `rev.clj`
    or a doc block consolidating it.
  - table view-state shape spec.
  - saga idempotency-key format as a stability contract.
  - `*effect-sink*` threading assumption.
- Widget catalog (`:c/widget` → ns → EDN option keys).
- `forms/README.md` — one line per demo form: which feature it exercises.
- ns-level "see also" links across the route→workflow→cell indirection.
- Resolve `§tag` refs (esp. lowercase `§lqj`/`§8gj`/…) to PLAN.md; surface PITFALLS.

## Phase 5 — Store consistency (optional, lower priority)
- Extract a `store/sqlite` helper (datasource + schema DDL + edn-column read/write)
  shared by `SqlStore`/`SqlFormStore`; the `IAtom`-default + `case backend` init are
  copy-pasted between documents and forms.
