# Stepvine Architecture

A server-authoritative reactive form/app engine. The browser is a **dumb
terminal**: it renders HTML, binds Datastar signals, and POSTs intents. All state,
computation, validation, and workflow live on the server; every change is pushed
back over an SSE stream. Apps are hot-swappable **EDN (+ CSS)** in a store.

This document is the map: read it before changing anything non-trivial. It names
the layers, the one request flow every feature follows, and the **hidden
invariants** that an edit can silently break.

---

## The three engines

| Engine | Where | Role |
|---|---|---|
| **Domino** (reactive) | `editor/*.cljc` (vendored seam) | A form is a reactive DAG: model fields → events → reactions. `d/transact` recomputes derived values and emits effect *intents*. |
| **Mycelium** (workflow FSM) | `cells/*`, `workflows/*` | Every HTTP route is a workflow pipeline of *cells* (parse → load → guard → commit). Document lifecycle is an FSM (states + guarded transitions). |
| **Datastar** (SSE) | `hub.clj`, `web/sse.clj` | Per-document fan-out. The server patches signals/elements; the browser reacts. No client logic. |

---

## The one request flow (memorize this)

Every feature is a variation of this chain. Example: **a single field edit**.

```
browser  POST /doc/:id/field/:fid          (Datastar signals in the JSON body)
  │
web/routes/pages.clj                        route → (ds (post form/update-field))
  │                                          `ds`=datastar-CSRF, `post`=mycelium handler
workflows/form.clj   update-field           pipeline [:start :apply] → cell KEYWORDS
  │                                          :form/parse-field → :form/apply-field
cells/form.clj       :form/apply-field       guards (lock / role / state) then →
  │
session.clj          apply-field-as!         lock-aware; → editor/save-ids! or apply-change!
  │
editor.cljc          swap-session!           THE mutation primitive (serialized per session)
  │                    └─ impl/apply-changes → editor/data.cljc transact-ctx (domino transact)
  │                    └─ :on-update hook  ◄── *** broadcast happens HERE, not in the cell ***
session.clj          on-update (init-key)    save-db! (bumps :rev) + broadcast-changes!
  │
hub.clj              broadcast-signals!       SSE patch to every connection → browser updates
```

**The two traps in this flow:**
1. **Cells are keywords, not functions.** `:form/parse-field` is a `myc/defcell`
   dispatch key — "go to definition" fails. Grep the *string* `:form/parse-field`.
   A route's logic lives in `cells/<x>.clj`, not in the `workflows/<x>.clj` def
   (which is only pipeline topology).
2. **The SSE broadcast is a side effect of `swap-session!`'s `:on-update` hook**
   (wired in `session.clj`'s `ig/init-key :session/manager`). It is invisible from
   the cell. If you edit a cell that calls `apply-field-as!`/`apply-change!`, the
   broadcast already happened — do not add another.

### Other flows (same shape)
- **Workflow action** (`POST /doc/:id/wf/:action`): `workflows/workflow.clj` FSM →
  `cells/workflow.clj` (parse → load → guard → effects → commit | reject) →
  `directives.clj` (compile to a directive list) → `effects.clj` (`run-saga!`).
- **Collection / nested-collection edits**: `web/collection_entry.clj`,
  `web/collection_item.clj` are parse→`session/*`→`cells.form/rerender-collection!`
  →broadcast handlers (the cell pattern, living in `web/` for historical reasons).

---

## Layers & dependency direction (down only)

```
web/*            routes, middleware, ring handlers, page layout, SSE transport
  │
cells/* · workflows/* · directives.clj · effects.clj      request → engine ops
  │
session.clj · documents.clj · forms.clj · users/access/audit      live state + stores
  │
editor/*.cljc (Domino seam)                                the reactive engine
```
`render.clj` + `components/*` (widget multimethod) sit beside this — the view layer,
fed a render *context* projected from a session.

---

## Stores (pluggable behind protocols + Integrant)

- `documents.clj` — `DocStore` protocol; `SqlStore` (SQLite, EDN-in-`doc`-column +
  indexed owner/status) or an atom/duratom.
- `forms.clj` — `FormStore`; working form + immutable version archive + a **draft**
  slot; `SqlFormStore` or map/disk.
- `users` / `access` / `audit` — bare atom/duratom (not yet protocol-backed).
- Effects transports: `mailer.clj` (`Mailer`), `http.clj` (`HttpClientP`) — recording
  impls in dev/test, real (SMTP / java.net.http) in prod.

---

## Hidden invariants — the traps that bite

These are cross-cutting contracts that no type enforces. Breaking one fails
silently (wrong value, dropped effect, false conflict) — not with an error.

1. **Signal-name encoding** (`render/signal-name`). Field id → Datastar signal name:
   non-alphanumerics → `_`, leading/trailing `_` trimmed (`:overweight?`→`overweight`,
   `:bmi-category`→`bmi_category`). The **render side encodes** and the **read side
   must reconstruct the identical name** to fetch a posted value
   (`cells/form.clj`, `session/lock-signal-map`). Change the regex on one side only →
   value reads return nil, no error. Collection signals are `<coll>_<idx>_<field>`,
   nested are `<coll>_<idx>_<coll2>_<idx2>_<field>` (built by `render/item-signal-name`
   from the item path).
2. **`:meta` map schema** (on every document). See the schema block in `documents.clj`.
   Keys are written from many sites (`documents.clj`, `effects.clj` writes
   `:reports`/`:effects`, `directives` `:set-meta` can write *any* path). There is no
   validation — `update-meta!` trusts the caller.
3. **`:rev` is top-level, NOT in `:meta`** (optimistic concurrency token). `save-db!`
   bumps it on every **data** save and broadcasts `{"rev" …}` so each client's `$rev`
   stays current; lifecycle/meta changes do **not** bump it (so a client's own
   submit/revise never self-stales). Seed → bump → check: `cells/document.clj`
   (`:rev` into ctx) → `documents/save-db!` → `documents/rev-current?` +
   `cells/workflow.clj posted-rev` + `:wf/guard :stale`. Moving `:rev` into `:meta`
   breaks the broadcast and every guard.
4. **Table view-state is session state, NOT document data.** `{:view-state {coll-id
   {:sort :filter :page :order :cols}}}` lives on the session atom and survives
   transacts (apply only touches `::data/ctx`). Domino knows nothing about it. See the
   shape doc in `session.clj`.
5. **Saga idempotency key** = `"<from-state>/<action>/<position-index>"`
   (`directives.clj`). A workflow action's side-effect steps skip if already logged
   `:ok` under this key (so a retry of an uncommitted transition is at-most-once).
   **Reordering directives shifts the positional index** → previously-completed steps
   re-run. The transition commits only after the saga succeeds.
6. **`*effect-sink*` dynamic var** (`editor/data.cljc`). Domino emits effect intents
   by `swap!`-ing this thread-bound atom *during* `transact-ctx`; they are drained to
   `::emitted` and surfaced via `impl/emitted-effects`. It is thread-local-per-transact
   — fragile if transact ever goes async/parallel.

---

## Feature map (where each feature lives)

| Feature | Primary code | Demo form |
|---|---|---|
| Reactive calc / cascades | `editor/data.cljc`, `cascades.clj` | `order.edn`, `booking.edn` |
| Field locking (multi-user) | `editor/locks.cljc`, `session.clj` | any shared doc |
| Validation | `validation.clj` (compiles to reactions) | `event.edn` |
| Sources / imports / typeahead | `sources.clj`, `imports.clj`, `web/search.clj` | `lookup.edn`, `intake.edn` |
| Collections / tables / nesting | `components/widgets/tables/*`, `session.clj` | `roster.edn`, `tasks.edn`, `teams.edn` |
| Workflow FSM + actions | `workflows/`, `cells/workflow.clj`, `directives.clj`, `effects.clj` | `ticket.edn`, `case.edn` |
| Multi-step resilience (retry/compensate) | `effects.clj` (`run-saga!`) | `ticket.edn` approve |
| Optimistic concurrency (`$rev`) | invariant #3 above | `ticket.edn` |
| Submission / approval / PDF | `documents.clj`, `cells/document.clj`, `pdf.clj` | `case.edn`, `ticket.edn` |
| Granular permissions / RBAC | `access.clj`, `render/perm-ok?` | `review.edn`, `triage.edn` |
| Form builder + live editor + drafts | `web/editor.clj`, `forms.clj`, `builder.clj` | the `/admin/forms` UI |
| Document content search | `documents/search-accessible`, `web/doc_search.clj` | any |
| Auth / OAuth | `auth.clj`, `web/auth.clj`, `web/oauth.clj`, `users.clj` | — |

**The `forms/*.edn` demo forms are worked examples** — one per feature. `showcase.edn`
is the broad demo. To learn a feature, read its demo form + the code above.

See also: `README.md` (build/run), `PLAN.md` (§-tagged roadmap), `REFACTOR_PLAN.md`
(structure), `forms/README.md` (per-demo index).
