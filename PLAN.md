# Stepvine — Backend-Driven Form Builder

> A server-authoritative platform for building, hosting, and concurrently editing
> data-entry forms. The browser is a **dumb terminal** (Datastar SSE); all form
> logic, reactive computation, validation, and side effects run on the server.
> The form **document** is a Domino schema — the single source of truth.

This file is the consolidated plan + architecture reference. It describes the
current system and the remaining roadmap.

---

## 1. Goals & Principles

1. **Server is authoritative.** No business logic in the browser. The DOM is a
   projection of server state; the client only reports user intent and renders
   patches it is handed.
2. **The Domino document *is* the schema.** A form's `:model` / `:events` /
   `:reactions` define data shape, derived fields, cascades, and display
   boundaries. Everything else (views, imports, exports) hangs off it.
3. **Declarative data relationships.** Derived fields, external lookups, DB-sourced
   dropdown options, and cross-field validation are all expressed in the form EDN,
   not hand-wired in handler code.
4. **One mechanism per concern.** Domino = per-document reactive state. Mycelium =
   per-request orchestration. Datastar = transport + DOM binding. They don't
   overlap.
5. **Backend-swappable stores.** Every store (`:store/forms`, `:store/documents`,
   …) exposes a small API. Disk-backed atoms/duratom today; a query DB
   (XTDB/Datalevin) tomorrow behind the same API.
6. **LLM friendly** The system must provide APIs for the LLM (or any external client)
   to hook into and edit the document the same way the user would through the UI.
 
---

## 2. The Three Pillars

| Layer | Library | Responsibility | Scope |
|-------|---------|----------------|-------|
| **Reactive state** | **Domino** (`domino/core 0.4.0`) — a model + events + effects DAG. *Reactions* (eager display values) and *collections* (per-item subcontexts), which current domino does not provide, are reconstructed in the editor seam. | The form's data graph: model paths, events (cascading derived fields), effects, and (editor-provided) reactions. | Per **document/session** |
| **Orchestration** | **Mycelium** | Each HTTP route is a compiled workflow of pure `defcell`s: parse → resolve session → transact → diff → render → respond. Resources (stores, clients) injected per request. | Per **HTTP request** |
| **Transport / UI** | **Datastar** (`dev.data-star.clojure/sdk` + `…/ring`) | `data-*` attributes bind the DOM to server-owned **signals**; user events POST intent; the server pushes `PatchElements` + `PatchSignals` over SSE. | Per **connection** |

Domino and Mycelium are **complementary, not competing**: Mycelium handles the
*request*, Domino handles the *document*. A Mycelium cell calls into the Domino
session; Domino's outgoing effects can themselves be Mycelium cells/resources.

```
Browser (Datastar, dumb terminal)
   │  data-on:input → @post('/doc/:id/field/:fid')       (user intent)
   ▼
Mycelium workflow-handler
   │  cells: parse → load-session → transact → diff → render → respond
   ▼
Domino engine (yogthos.stepvine.editor)
   │  events recompute derived fields (BMI); reactions recompute display values
   ▼
Diff old vs new signals  (session manager :on-update hook)
   ▼
Datastar SSE: patch-signals! (changed field/reaction/lock values)
   ▼
Browser DOM updates in place
```

---

## 3. The Form Document — Single Source of Truth

A form is an EDN file with embedded Clojure functions (evaluated safely via
**sci**). The `:data` section **is** the Domino schema:

```clojure
{:id :bmi
 :title "BMI Calculator"
 :version 1

 :data
 {:model
  [[:patient
    [:weight {:id :kg  :type :number}]
    [:height {:id :m   :type :number}]
    [:bmi    {:id :bmi :type :number}]]]

  :events
  [{:id      :calc-bmi
    :inputs  [:kg :m]
    :outputs [:bmi]
    :handler (fn [{{:keys [kg m]} :inputs}] {:bmi ((fnil / 0 1 1) kg m m)})}]

  :reactions
  [{:id :overweight?  :args [:bmi] :fn (fn [bmi] (>= (or bmi 0) 25))}
   {:id :bmi-category :args [:bmi] :fn (fn [b] (cond (nil? b) "n/a" (< b 18.5) "underweight" ...))}]}

 :imports
 {:patient {:trigger :patient-id :mapping {:fname [:given] :lname [:family]}}}

 :exports
 {:summary {:label "Export" :template {:weightKg :kg :bmi :bmi :category :bmi-category}}}

 :views
 {:default
  {:title "BMI Calculator"
   :opts  {:widget-namespaces {"c" "stepvine.components"}}
   :markup
   [:c/form {}
    [:c/input-field {:label "Weight (kg)" :id :kg}]
    [:c/input-field {:label "Height (m)"  :id :m}]
    [:c/input-field {:label "BMI" :id :bmi :read-only true}]
    [:p "Category: " [:stepvine.util/value {:rxn :bmi-category}]]
    [:c/show {:when :overweight?} [:p.warning "Patient is overweight."]]
    [:c/action {:action :summary :label "Export summary"}]]}}}
```

Key properties:
- **`:data` is a Domino schema** — model paths carry field `:id`/`:type`; events
  are DAG-ordered cascading derived fields; reactions are pure display
  computations (become Datastar signals).
- **`:imports`** declare external-service hydration triggers and field mappings.
- **`:exports`** are templated outputs with placeholder substitution.
- **`:views`** are hiccup markup with namespaced widget keywords. A view never
  owns state — it references field/reaction ids. The `:opts {:widget-namespaces}`
  map aliases a short prefix (`"c"`) to a widget vocabulary (`"stepvine.components"`).
- **`:options {:source …}`** on a field def references a named option set
  (DB-sourced dropdowns), resolved at render time.

### 3.1 Collections

A field with `:collection? true` holds a map of items, each with its own schema
(model + events + reactions). Per-item derived fields recompute independently.
Current domino has no nested contexts, so the editor projects each item onto
plain nested paths `[coll idx field]` (with synthetic flat ids for domino's event
graph) and rebuilds a *live schema* — base model/events plus one set per current
item — from the db on every transact:

```clojure
[:members
 {:id :members :collection? true :index-id :mid
  :schema
  {:model    [[:first {:id :first}] [:last {:id :last}] [:full {:id :full}]]
   :events   [{:inputs [:first :last] :outputs [:full]
               :handler (fn [...] {:full (str first " " last)})}]
   :reactions [{:id :initials :args [:first :last]
                :fn (fn [f l] (str (first f) (first l)))}]}}]
```

---

## 4. Runtime Architecture

### 4.1 Components (Integrant, see `resources/system.edn`)

| Component | Responsibility | Backing store |
|-----------|---------------|---------------|
| `:store/forms` | Form **definitions** (templates). One EDN file per form. | Disk (`forms/` dir) |
| `:store/documents` | **Document instances** (form + data). | `duratom` (crash-safe) or atom |
| `:store/options` | Named dropdown option sets (reference data). | Disk (`options/` dir) |
| `:store/users` | User accounts + password hashes. | `duratom` or atom |
| `:session/manager` | Live editing sessions (Domino ctx + locks + connections). | In-memory atoms |
| `:datastar/hub` | Open SSE generators per document (fan-out broadcast). | In-memory atom |
| `:datastar/heartbeat` | Periodic keep-alive pings to detect dropped SSE clients. | — |
| `:clients/patient` | External service stub for patient lookups. | In-memory map |

### 4.2 Request Lifecycle

Every route is a Mycelium workflow — a pipeline of `defcell`s:

| Route | Workflow | Output |
|-------|----------|--------|
| `GET /` | List the user's documents | HTML (Selmer shell + hiccup list) |
| `GET /doc/:id` | Ensure session → render full page + seed signals + open SSE | HTML + `data-signals` |
| `GET /doc/:id/sse` | Open SSE stream, register in hub, sync populated signals | SSE (long-lived) |
| `POST /doc/:id/field/:fid` | Parse value → coerce → transact (lock-aware) → broadcast | 204 + SSE patches |
| `POST /doc/:id/field/:fid/lock` | Acquire lock → broadcast lock state | 204 |
| `POST /doc/:id/field/:fid/unlock` | Release lock → broadcast lock state | 204 |
| `POST /doc/:id/coll/:coll/add` | Add item → recompute → re-render collection → broadcast | 204 + SSE patches |
| `POST /doc/:id/coll/:coll/:idx/remove` | Remove item → recompute → re-render → broadcast | 204 + SSE patches |
| `POST /doc/:id/coll/:coll/:idx/field/:fid` | Parse → coerce → transact (lock-aware) → broadcast | 204 + SSE patches |
| `POST /doc/:id/action/:aid` | Run export → render result → broadcast | 204 + SSE patches |
| `POST /doc/:id/build` | Form builder: generate + save a form from the builder document | 204 + SSE patches |
| `POST /form/:id/new` | Create document → redirect | 302 |

### 4.3 Session Model

The Domino session/editor layer is **vendored** under `yogthos.stepvine.editor.*`
(`.cljc`, frontend-agnostic). Key functions:

- `impl/create-session` — initialize a Domino context from a form + initial db
- `impl/apply-changes` — transact field changes, triggering Domino events/reactions
- `impl/value` — read the current value of a field or reaction
- `impl/db` — the raw Domino db
- `e/lock!` / `e/unlock!` / `e/save-ids!` — multi-user field locking
- `e/disconnect!` — release all locks on disconnect

The session manager's `:on-update` hook (a) persists the Domino db to the
document store and (b) diffs old vs new signals and broadcasts changed ones over
the Datastar hub. This is the reactive multi-user loop.

---

## 5. Server-Side Widget Rendering

A **server-side multimethod** (`render/render-widget`) emits HTML with Datastar
`data-*` bindings (dispatch on a resolved, alias-expanded widget keyword).

### 5.1 Widget Taxonomy (current)

| Widget keyword | Renders | Key bindings |
|---|---|---|
| `:c/form` | `<div>` with `data-signals` seed + `data-init` SSE open | Seeds all field/reaction/collection signals; opens the SSE stream on load (`data-init`, **not** `data-on:load`) |
| `:c/input-field` | `<div.field>` with `<label>` + `<input>` | `data-bind="<sig>"` (value form) + debounced `data-on:input` POST; `readonly` if `:read-only`; `data-attr:disabled` if locked-by-other; focus/blur → lock/unlock; error text from a reaction |
| `:c/dropdown-select` | `<div.field>` with `<label>` + `<select>` + `<option>`s | Item-aware like input-field; options from `:options {:source …}` (or static `:values`) |
| `:c/show` | `<div>` with `data-show` bound to a reaction | Content visible only when the reaction is truthy |
| `:c/action` | `<button>` that POSTs to `/doc/:id/action/:aid` | Triggers export; result patched into a target element |
| `:c/collection` | Container `<div>` with per-item template + add/remove buttons | Each item has scoped signals `<coll>_<idx>_<field>` |
| `:c/build-button` | `<button>` (form builder only) | POSTs to `/doc/:id/build` |
| `:stepvine.util/value` | Inline `<span>` with `data-text` bound to a field/reaction value | Renders derived values inline |

Both `input-field` and `dropdown-select` are **item-aware**: inside a collection
they bind the item-scoped signal and POST to the collection-item endpoint; only
top-level fields get a stable `id`/`name` (item ids would collide). Item-field
dropdown option sources are resolved via `render/all-field-opts` (top-level +
every collection's item field-opts).

### 5.2 Render Context

Built from the live session by `render/session->context`:

```clojure
{:values      {field-id -> current-value}     ;; top-level fields
 :rxns        {reaction-id -> current-value}   ;; reactive display values
 :field-opts  {field-id -> {:type … :required? …}}
 :collections {coll-id -> {:order [idx…] :field-opts {} :items {idx {fid val}}}}
 :item        {:coll :members :idx "abc123"}   ;; set when inside a collection
 :options     {field-id -> [{:value … :label …}]}  ;; resolved option sources
 :aliases     {"c" "stepvine.components"}       ;; widget namespace expansion
 :doc-id      "uuid…"}
```

### 5.3 Signal Naming

`render/signal-name` collapses non-alphanumerics to `_` and strips leading/trailing `_`:

- Top-level field `:kg` → signal `kg` (referenced as `$kg`)
- Reaction `:overweight?` → signal `overweight`; `:bmi-category` → `bmi_category`
- Collection item field `:members` / `"abc"` / `:first` → signal `members_abc_first`

### 5.4 Implementation notes (hard-won Datastar contracts)

- **`null` deletes a signal.** `patch-signals` is JSON-merge-patch, so a `null`
  value *removes* the signal (destroying its `data-bind`). Field/reaction signals
  are therefore emitted as `""` when empty, never `null`, everywhere they're
  synced (`session->signal-map`) and seeded (the `:c/form` `data-signals`).
- **On-connect sync must not clobber edits.** datastar loads as an async ES
  module and only takes control of inputs after `data-init` opens the SSE stream;
  a full-map sync arriving mid-edit would reset a just-typed value. The page seed
  already carries current state, so the SSE on-connect sync sends only *populated*
  signals (catch-up for real data, never overwriting empty fields being edited).
- **Field POSTs read the sanitized signal name.** A hyphenated field id (`:form-id`)
  has a sanitized signal (`form_id`); the POST handler reads the posted signal by
  the sanitized name, not the raw path-param.
- **`data-bind` is the value form** (`data-bind="kg"`); the key form
  (`data-bind:kg`) is "exclusive" (key XOR value) and trips `KeyAndValueProvided`
  when hiccup renders a value.

---

## 6. Multi-User Model

### 6.1 Field Locking

Locks are per-field (and per-collection-item-field):

1. User focuses an input → `data-on:focus` POSTs `/lock`.
2. Server acquires the lock for `uid` on `field-id`; broadcasts the `locks` signal map.
3. Other clients see `locks.kg = "other-uid"` → the input gets `disabled`.
4. User blurs → POSTs `/unlock`; server releases; broadcasts updated locks.
5. On SSE disconnect, all of that client's locks are released.

Lock state is a flat signal map `{"kg" "uid-1", "members_abc_first" "uid-2", …}`,
patched whole on each change (JSON merge-patch; `nil` clears a lock).

### 6.2 Presence

The `presence` signal tracks the number of distinct users connected to a
document, broadcast on connect/disconnect.

### 6.3 Consistency

- **Lock-aware saves:** if another user holds the lock, `apply-field-as!` returns
  `:rejected` and no change is transacted.
- **Lock-free saves:** if no lock exists (debounce/blur race), apply anyway.
- The session manager serializes all changes through a single atom swap; two
  concurrent writes to the same field are ordered.

---

## 7. Data Relationships

All four kinds are declared in the form definition:

1. **Derived fields (Domino events).** BMI recalculates when weight or height
   changes. Declared once; zero view code. Chained derivations (A→B→C) just work.
2. **External service imports.** When `:patient-id` changes, fetch from the
   patient-client and transact mapped fields (`:fname`, `:lname`, `:dob`) into the
   document. Recompute + broadcast.
3. **DB-sourced dropdown options.** A field declares `:options {:source :clinics/active}`;
   options are resolved at render time from `:store/options`. Swappable to a real
   DB query.
4. **Cross-field validation (reactions over reactions).** `:form-valid?` =
   `(and (nil? weight-error) (nil? height-error))`. A reaction can depend on other
   reactions, building validation chains.

---

## 8. Document Access Control

```
Owner (created-by) ─── can edit, share, delete
Shared users        ─── can edit (access via /doc/:id)
Anonymous           ─── no access (redirected to /login)
```

- `documents/can-access?` gates every `/doc/:id` route via `wrap-doc-access`.
- `documents/share!` adds a user to the `:shared` set.
- Auth: bcrypt-hashed passwords, ring session cookie, CSRF protection on HTML
  forms; datastar endpoints are gated by the `Datastar-Request` header.

---

## 9. Concept Map — Form-Manager Concepts → Stepvine

### 9.1 Form Definition

| Document-manager concept | Stepvine equivalent | Status |
|---|---|---|
| `:model` with `:path` | Domino `:model` with `:id` | ✅ done |
| `:validation` per field | Domino `:reactions` (field-error reactions) | ✅ done |
| `:validate-when` preconditions | Reaction over reaction (`:form-valid?`) | ✅ done |
| `:business-rules` (action) | Domino `:events` (input→output cascade) | ✅ done |
| `:business-rules` (trigger-action) | Domino effects | 🔜 planned |
| `:business-rules` (row/table-action) | Collection (subcontext) events + add/remove | ✅ done |
| `:functions` | Inline `:handler` fns (sci-evaluated) | ✅ done |
| `:ui-rules` (visibility, required, errors) | Reactions + `data-show`/`data-attr` | ✅ done |
| `:reports` (hiccup templates) | `:exports` with placeholder substitution | ✅ done |
| `:views` | `:views` with `:markup` (hiccup + widget keywords) | ✅ done |
| `:metadata` | Top-level `:id`/`:title`/`:version` | ✅ done |
| `:datasources` / `:hydrations` | `:imports` (`:trigger` + `:mapping`) + `:options` sources | ✅ done |

### 9.2 Workflow / Document Lifecycle

| Concept | Stepvine equivalent | Status |
|---|---|---|
| Workflow metadata + listing | Form `:id`/`:title`; document list page | ✅ done |
| Pages (index, documents) | `/` (landing), `/doc/:id` (editor), `/doc/:id/sse` (stream) | ✅ done |
| Index search | `:imports` with `:trigger` field + patient-client | ✅ done |
| Document create / open | `POST /form/:id/new`; `GET /doc/:id` (page + SSE) | ✅ done |
| Document actions | `POST /doc/:id/action/:aid` (exports) | ✅ done |
| State-change / email / save-pdf actions | document `:state` transitions + effect cells | 🔜 planned |
| Multi-stage workflow (states) | `:state` + allowed-transitions | 🔜 planned |

### 9.3 Widget System

| Concept | Stepvine equivalent | Status |
|---|---|---|
| `render-widget` + context | `render/render-widget` multimethod | ✅ done |
| `:get` / `:save` | Datastar `data-bind` + `data-on:input` POST; server owns state | ✅ done |
| `:lock` / `:unlock` / `:locked-from-us?` | `/lock` `/unlock` + `locks` signal map; inputs disable when locked by another | ✅ done |
| `:read-only?` | `:read-only` attr → `readonly` | ✅ done |
| `:validate` / `:visible?` | Reaction-valued `:error` attr / `data-show` | ✅ done |
| Table add/remove/move/clear | Collection widget add/remove with element-patch re-render | ✅ add/remove done |
| Widget taxonomy | `form`, `input-field`, `dropdown-select`, `show`, `action`, `collection`, `build-button`, `value` | ✅ core done |
| `:datasource` typeahead | `:imports` trigger + `:options {:source …}` | ✅ done |

---

## 10. Key Decisions (resolved)

1. **HTTP server for SSE — ring-jetty9 (info.sunng), sync mode.** The Datastar
   *ring* adapter writes the response body synchronously and the jetty9 async
   handler completes the response as soon as that write returns — so async mode
   closes the stream after the first event. The adapter keeps a stream open only
   by **blocking in `on-open`**, which works identically in sync mode and lets all
   ordinary handlers stay plain `[req]->resp` fns. We run **sync Jetty with a
   raised `:max-threads`**. *Trade-off:* one server thread per open SSE connection;
   a `:datastar/heartbeat` ping detects abruptly-dropped clients (frees the thread
   + releases that client's locks). If connection counts get high, the http-kit
   adapter (true async) is the swap — the SSE hub is abstracted so the change stays
   localized.
2. **Persistence — atom + duratom** for documents/users, behind store
   abstractions so a query DB (XTDB/Datalevin) can slot in later. Form definitions
   and option sets live on disk (one EDN file each), scanned from `forms/` and
   `options/`. **User-created content (forms, dropdown values) goes in
   storage/on-disk, never in `system.edn`.**
3. **Multi-user from the start.** Locking is enforced in the core reactive loop;
   presence + lock state are broadcast over SSE; the hub is keyed by
   *document → {connection → sse-gen, user}*.
4. **sci sandboxing** for evaluating form `:event`/`:reaction` fns (safe, no
   trusted-eval).
5. **Editor ported to current domino (`0.4.0`).** domino `0.4.0` is a ground-up
   rewrite — a model + events + effects DAG exposing only
   `transact`/`initialize`/`initial-transaction`/`trigger-effects` — that dropped
   the native reactions and collections (subcontexts) the editor relied on. The
   editor seam (`editor.data`) now reconstructs both on top of the new
   primitives, so we track the maintained library instead of a legacy alpha:
   - **Reactions** are computed by the editor (eager, dependency-ordered) rather
     than modelled as lazy events; they read back through `get-value` like fields.
   - **Collections** project each item onto nested paths with synthetic flat ids,
     rebuilding a live schema (base + per-item model/events) from the db on every
     transact — re-init preserves event laziness for free.
   - **Lock relatedness** is recomputed from the events graph; an item field's
     lock-parent is its *item*, so different items edit concurrently.

   This also removed the stray `random-uuid` compile warnings (domino `0.4.0`'s
   `domino.util` excludes it; our `editor.util` excludes it unconditionally).

---

## 11. Phased Implementation Plan

### ✅ Done

**Phase 1 — Skeleton + static render.** Kit skeleton, EDN form definitions,
Selmer page shell, `:store/forms`/`:store/documents`, server-rendered form with
Datastar bindings, static BMI form.

**Phase 2 — Reactive server.** Domino engine in session; Mycelium cells for field
updates; `data-signals` seed + `data-init` SSE open; `patch-signals!` broadcast;
BMI recalculates live.

**Phase 3 — Multi-user.** Datastar hub (per-document fan-out), field locking
(acquire/release/broadcast), presence tracking, lock-aware saves.

**Phase 4 — Data relationships.** External imports (patient-client stub),
DB-sourced dropdowns, collections (subcontexts) with per-item derived fields,
cross-field validation.

**Phase 5 — Mycelium refactor.** All form routes as Mycelium workflows; `cells/form`,
`cells/document`, `workflows/*`; collection + lock/unlock cells.

**Phase 6 — Form builder + exports.** Visual form builder (itself a Stepvine form),
`build-form` → `forms/save-form!`, templated exports, form migrations
(`:version` + migration fns).

**Cross-cutting (done).** Auth (bcrypt + ring session + CSRF), per-document ACLs,
SSE resilience (heartbeat keep-alive + datastar client reconnect), and an
end-to-end **Playwright storyboard** (`e2e/storyboard.mjs`) plus a unit/integration
suite covering all of the above.

### 🔜 Planned

Phases 7–11 are specified in detail in **§15 (Reference-Guided Implementation
Roadmap)**, derived from a study of two sibling Clojure systems — a form/document
engine and a workflow-orchestration layer. Headlines:

**Phase 7 — Versioning & document store schema. ✅ done.** Immutable form-version
archive (`yogthos.stepvine.versions`) with content digests and sealed superseded
versions; `:store/forms` publishes every authoring form on boot. Documents pin
their exact `[:form-version :form-digest]` at creation; `docs/ensure!` loads that
frozen version (no auto-migration), and `docs/rebase!` is the opt-in forward path
(snapshotting the pre-rebase db). The document record now carries `:status`,
`:owner`, `:rev` (optimistic-concurrency token) and a system `:meta` map
(§15.1–15.2). *Deferred to Phase 8:* using `:rev` to reject stale writes, the
`:meta` write allowlist, and a drafts authoring flow.

**Phase 8 — Lifecycle, audit & submission. ✅ core done.** Durable append-only
audit log (`yogthos.stepvine.audit`) with before/after diffs and a per-document
view, fed by field-saves and lifecycle transitions; writes are fire-and-forget
and never crash the caller. Per-view submission + an append-only approval log +
an immutable snapshot (`documents/submit!` / `revise!`), guarded by a sole-editor
check and an optional validity reaction (`:submit-when`); finalized documents are
**hard read-only** — the field-save cell rejects writes (409) and the form
renders disabled with a banner, flipped live via a `$locked` signal (§15.4–15.5).
*Deferred to Phase 11 (multi-node):* using `:rev` to reject stale writes and
moving lock/presence into a shared store (§15.3 — only needed off single-node).

**Phase 9 — Pluggable sources, imports, validation & partials. ✅ done.**
`yogthos.stepvine.sources` — one `resolve-source` multimethod on `:kind`
(`:static`, `:options`, `:client`, host-allowlisted `:http`); `options` delegates
to it. `imports` rewritten lazy + diff-based + chainable, fetching via named
sources. `validation` — a `:validation`/`:validate-when` vocabulary compiled to
Domino error reactions + a `:valid?` reaction (which `:submit-when` gates on).
`partials` — `{:include id}` blocks spliced into forms at serve time. Forms are
served through splice→compile; all sci-sandboxed, never `clojure.core/eval`
(§15.6–15.9).

**Phase 10 — Workflow orchestration. ✅ core done.** The document state machine
is expressed as a **mycelium FSM** (states=cells, transitions=`:edges`,
guard=`:dispatches` over `:permitted?`, `:default`→reject): `yogthos.stepvine.
workflows.workflow` runs load → guard → effects → commit / reject. A form declares
a validated `:workflow` (states + transitions + per-action `:steps`);
`workflow.clj` holds the pure helpers + a pluggable `run-step` multimethod
(`:notify`/`:snapshot`/`:set-field`/`:set-meta` with `{:from path}` resolution);
`directives.clj` applies the emitted directives in-process (transition w/ history
+ lock, field/meta writes, notify, snapshot — recompute/persist/audit/broadcast).
`:workflow` widget + `forms/ticket.edn` demo; storyboard drives open→review→closed
(§15.10–15.12). *Deferred:* a uniform external-client protocol and multi-step
compensation (mycelium `:error-groups`/`:resilience` are the hooks) — needed once
real external steps (email/PDF/HTTP) land.

**Phase 11 — Pages, index lookups & production hardening.** Multi-document
workflow pages, index-based document creation/search, real query-DB backends,
PDF generation, OAuth2/OIDC (§15.13).

---

## 12. Code Inventory

### Vendored editor layer (`yogthos.stepvine.editor.*`)

The Domino session/editor layer (originally adapted from a Reagent/Sente form
editor) is vendored under `src/clj/yogthos/stepvine/editor*` — a self-contained
`.cljc` lib depending only on `domino` + `sci`:

- `editor` — `session-manager`, `create-session!`, `lock!`/`unlock!`, `save-ids!`, `disconnect!`
- `editor.impl` — sci evaluation, `create-session`, `apply-changes`, `value`, `db`
- `editor.locks` — multi-user field lock manager (related-field locking)
- `editor.data` — the **domino adapter seam**: `initialize-ctx`/`transact-ctx`,
  value reads, field-opts, the relatedness/parents graph, and the
  reactions + collections reconstruction described in §10.5
- `editor.actions`, `editor.util` — action map + uuid/util helpers

### Net-new Stepvine code

| Namespace | Role |
|-----------|------|
| `…core` / `…config` | System init (Integrant graph) + profile config |
| `…session` | Session-manager component over the editor layer; on-update persistence + broadcast |
| `…render` | Server-side widget renderer: dispatch, signal naming, markup walk, collections, option resolution |
| `…hub` | Datastar connection hub: register/unregister/broadcast/heartbeat |
| `…forms` / `…documents` / `…options` / `…users` | Stores (disk EDN / duratom / atom) + access control |
| `…imports` / `…exports` / `…migrations` | Import hydration, templated export substitution, form versioning |
| `…builder` | Form builder: `build-form` from builder-document data |
| `…clients` | External service client component (patient stub) |
| `…docs` | Document session lifecycle: `ensure!` |
| `…cells.form` / `…cells.document` | Mycelium cells for field/lock/collection + document list/create/share/delete/export |
| `…workflows.form` / `…workflows.document` | Workflow compilations for routes |
| `…web.*` | Jetty server, ring handler + middleware, page/api routes, SSE handler, auth, security (CSRF + doc access), health |

### Configuration / data files

| File | Purpose |
|------|---------|
| `resources/system.edn` | Integrant system map (components, routes, env) |
| `resources/html/form.html` | Selmer page shell (Datastar CDN + minimal CSS) |
| `forms/{bmi,intake,builder,roster}.edn` | Form fixtures (calculator, imports+validation, meta-form, collections) |
| `options/{clinics,field-types}.edn` | Reference data for DB-sourced dropdowns |
| `data/*` | Persisted documents/users (duratom; gitignored runtime state) |

---

## 13. Testing

- **Unit / integration** (`test/clj`, `clojure -M:test`): renderer, signal maps,
  reactivity, collections, locks, migrations, documents, security/auth/ACLs/CSRF.
- **End-to-end** (`e2e/storyboard.mjs`, headless Chromium via Playwright): drives
  the running app through six workflows — auth gate, live BMI computation, export,
  collection add/derive/remove, visual builder (build + render + item-dropdown),
  and two-user live propagation + presence — asserting no uncaught page/console
  errors. `node e2e/storyboard.mjs` against the dev server on `:3000`.

---

## 14. Design Principles (recap)

1. **Server is authoritative** — the DOM is a projection of server state.
2. **The Domino document *is* the schema** — everything hangs off `:model`/`:events`/`:reactions`.
3. **One mechanism per concern** — Domino (state) · Mycelium (orchestration) · Datastar (transport).
4. **Declarative data relationships** — expressed in form EDN, not handler code.
5. **Backend-swappable stores** — small store APIs; disk/atom today, query DB tomorrow.

---

## 15. Reference-Guided Implementation Roadmap

This section is the detailed build guide for Phases 7–11. It distills the design
of two mature sibling systems — a **form/document engine** (forms, versioned
documents, sources, rules, audit) and a **workflow-orchestration layer** (multi-
document workflows, pluggable processors, external integrations) — into concrete
instructions for Stepvine, **adopting their proven patterns and fixing their
known weaknesses**.

**One structural advantage we keep.** In the reference, the document engine and
the orchestrator are *two services* that talk over signed HTTP (handoff JWTs,
cross-service `409`s, return-value hooks). Stepvine is a **single process**:
Domino owns state, Mycelium orchestrates, Datastar transports. So we get the
orchestration benefits *without* the dual-service machinery — the orchestrator
and the state owner are the same JVM. Where the reference returns "directives"
across a service boundary, we apply them as ordinary in-process changes. We keep
the **directive shape** anyway (steps return data, the document layer applies
it), because it keeps action steps pure and testable.

Each subsection follows: **Pattern** (what the reference does) → **Have** (our
current state) → **Build** (how to implement) → **Improve** (where we deliberately
diverge).

### 15.1 Form versioning & version pinning

**Pattern.** Forms are append-only rows keyed by a stable logical name; each save
is a new immutable version. A document, at *creation*, atomically resolves "latest
published version" and freezes that version's identifier on itself (a foreign
key). Every later load resolves the form by that frozen id, never by name — so
in-flight documents are immune to later edits of the form. Drafts are a flag that
excludes a version from "latest" resolution. There is **no** data-migration path:
old documents simply keep loading their old version forever.

**Have.** Forms are EDN files in `forms/` with a `:version` int; `migrations.clj`
can upgrade document data across versions; documents are persisted in a duratom
store. We already pin at render time by version.

**Build.**
- Make `forms` a **versioned store**, not a flat file read. Keep authoring as EDN
  on disk, but on load compute a content **`:digest`** (hash of the canonical EDN)
  and treat each `(form-id, version)` pair as an immutable artifact. The store
  API: `latest-published`, `get-version [id v]`, `publish! [form]` (assigns the
  next monotonic `:version`, refuses to overwrite an existing published version).
- **Pin at creation, atomically.** When `POST /form/:id/new` creates a document,
  resolve `latest-published` *once* and write `{:form-id … :form-version n
  :form-digest …}` onto the document record. `docs/ensure!` and `cells/document`
  load the form by `(form-id, form-version)` — never "current".
- **Drafts.** A `:draft? true` form version is visible only to its author/preview,
  excluded from `latest-published` and from the new-document picker.

**Improve.**
- **Enforce immutability** the reference only assumes. Publishing version *n* is
  write-once; a change is always a *new* version. The `:digest` makes this
  intrinsic and lets identical re-saves dedupe.
- **Keep our migration story** — the reference has none. A document may stay
  pinned forever (default), *or* be explicitly **rebased** onto a newer version
  via a declarative field-mapping migration (generalize `migrations.clj` into
  ordered `:migrations [{:from n :to n+1 :rename {…} :drop […] :via fn}]` on the
  form). Rebase is opt-in, audited, and snapshots the pre-rebase data.
- Put `:version`/`:draft?` **inside the validated form spec** so they cannot drift
  from a duplicated metadata copy (a real desync bug in the reference).

### 15.2 Document store schema (target shape behind the store API)

**Pattern.** A document row splits **user data** (the field values) from
**system/workflow metadata**, both as schemaless JSON; field writes are
*incremental per-path* via a deep-set primitive, never whole-document; there is no
version/owner/status column — all of that lives inside metadata.

**Have.** `documents` duratom holds whole documents; `session` holds the live
Domino db; on-update persists + broadcasts.

**Build.** Define this **target record** (the contract the store abstraction must
satisfy, whether backed by duratom today or XTDB/Datalevin/Postgres later):

```clojure
{:id           "uuid"
 :form-id      :intake
 :form-version 3                 ; pinned (§15.1)
 :form-digest  "sha256-…"
 :owner        "user-id"
 :status       :in-progress      ; :draft|:in-progress|:submitted|:completed|:cancelled
 :data         { …domino db… }   ; field values (the editor's persisted db)
 :meta         {:created-at … :created-by …
                :modified-at … :modified-by …
                :submitted-views #{}          ; per-view submission (§15.5)
                :approvals []                 ; append-only sign-off log
                :deleted? false :deleted-by nil
                :workflow {:state :draft :history []}}  ; §15.10
 :rev          17}               ; monotonic revision / optimistic-concurrency token
```

- Keep the **data / meta split**: clients can never write `meta` paths directly
  (an allowlist on the field-save cell rejects+logs attempts), so
  created-by/approvals/state can't be forged.
- Persist **incrementally**: the session already applies `[[path value]]` deltas;
  the store should persist the delta + bumped `:rev`, not re-serialize everything
  (we have editscript — use it as the deep-set equivalent).

**Improve.**
- Add an explicit **`:rev`** (the reference has none). Every write bumps it; the
  field-save cell can reject a stale-base write as a concurrency backstop, and a
  multi-node deployment can use it for compare-and-swap. This is the one piece of
  durable concurrency control the reference lacks.
- Keep `:status` as a **first-class indexed field** (not buried in metadata) so
  the document list can filter by it cheaply.

### 15.3 Editing, concurrency & durability

**Pattern.** Live edits flow over a socket; each document funnels *all* its writes
through a single serializing goroutine (no write-write races); clients update
optimistically and converge on server-broadcast authoritative values; field-level
**cooperative locks** live in one in-memory atom (hierarchical: locking a row
blocks its cells), released on disconnect; presence is computed from connected
sockets. Locks and presence are **not persisted** and are **single-node**.

**Have.** This is largely *done* and arguably cleaner than the reference: the
session-manager serializes per-doc writes via atom swap; Datastar SSE broadcasts
authoritative signal deltas; `editor.locks` does related-field (hierarchical)
locking; presence + lock maps broadcast over SSE; a heartbeat frees dropped
clients' locks.

**Build / Improve.**
- **Durability of locks/presence.** Today they're in-process like the reference.
  Define a `LockStore`/`PresenceStore` abstraction so a Redis/DB backing can slot
  in for multi-node — mirroring how `forms`/`documents` are already abstracted.
- **Never crash on a write/audit failure.** The reference calls `System/exit` when
  a DB/audit insert fails — a single transient error takes down every connected
  user. Our cells must instead surface a per-client error event and keep serving;
  audit writes go to a dead-letter, never to process exit.
- Use the new **`:rev`** (§15.2) to reject writes whose base revision is stale,
  closing the last-write-wins gap at path granularity.

### 15.4 Audit trail

**Pattern.** An append-only audit table `(id, time, actor-metadata, event)` fed by
an async channel; every state-changing action is logged with actor + payload
*before* execution. **Gaps the reference admits:** no before/after value diff, no
per-document index, and workflow state changes are *not* audited at all.

**Have.** No durable audit log yet.

**Build.** Add an `audit` store + a `cells/audit` write path:

```clojure
{:id "uuid" :at <inst> :doc-id "…" :by "user-id"
 :action :field/save                 ; :field/save :doc/submit :state/transition :action/run …
 :path [:weight] :before 70 :after 72   ; value-level diff
 :detail { … }}                      ; action-specific payload
```

- Write from the field-save / lock / submit / action cells, **fire-and-forget**
  but with a dead-letter on failure (never block the edit, never exit).
- Index by `:doc-id` + `:at` so "history of this document" is a cheap query.

**Improve over the reference on all three gaps:** record **before/after diffs**
(we already have old+new in the session swap), **index per document**, and
**audit every workflow transition** (the reference left this as a TODO).

### 15.5 Submission, approval & finalization

**Pattern.** Submission is **per view** (a document can be submitted for view A
while still editable for view B), recorded as a set of submitted view-ids plus an
append-only **sign-off log** `{view, user, time}`; un-submitting ("revise")
removes the view from the set but keeps the sign-off (audit). A sole-editor guard
blocks submit while other editors are present. **Weakness:** a "submitted"
document stays fully editable — only *re-submit* is blocked; the durable snapshot
is a separately generated report, not a freeze of the document.

**Have.** Exports (templated snapshots) exist; no submission/approval state.

**Build.**
- `:meta :submitted-views` (a set) + `:meta :approvals` (append-only
  `[{:view :default :by … :at …}]`). A `submit` action validates the view
  (§15.8), checks sole-editor (we already track presence), adds the view, appends
  the approval, and emits a **snapshot** (reuse `exports`) into a `reports` store.
- A `revise` action removes the view from the set, keeps the approval entry.

**Improve.** **Enforce read-only after submission** — the field-save cell consults
`:submitted-views` and rejects writes to a submitted view (the reference leaves
submitted documents editable, a real hazard for a sign-off system). The immutable
report snapshot becomes the legal artifact; the live document for that view goes
read-only until explicitly revised.

### 15.6 Pluggable data sources

**Pattern.** A form declares named sources; each is compiled at parse time into a
**callable fn** via a multimethod dispatched on `[location type]` (local/remote ×
search/fetch/http). Widgets call them through one uniform contract
(`(opts text [handler])` for search-as-you-type, `(opts)` for fetch). Remote calls
are **server-mediated** (the browser never calls the upstream directly), transit-
encoded, with the base host injected at call time. Sources are version-pinned with
the form (cached per form version). A small param allowlist guards requests.

**Have.** `options.clj` resolves field option lists from sources; `imports.clj`
hydrates. The pieces exist but aren't a single uniform abstraction.

**Build.** Generalize into one **source resolver** keyed by a single `:kind`:

```clojure
:sources
{:field-types  {:kind :static  :data [["Text" "text"] ["Number" "number"]]}
 :clinic-search {:kind :http-search :url "/clinics" :query-key :q
                 :allow #{:q :region} :host-allow ["clinics.internal"]}
 :patient       {:kind :http-fetch  :url "/patient" :key :mrn}}
```

```clojure
(defmulti resolve-source :kind)          ; → returns a fn
(defmethod resolve-source :http-search [spec] (fn [ctx query] …))
```

- Compile sources at form load, cache inside the parsed form (so they're
  **version-pinned** for free, like our reactions/events already are).
- Invoke **server-side** over the existing Datastar POST/SSE path (already our
  model — the typeahead/option widgets call a cell that resolves the source and
  patches results back). This is *natively* how Stepvine works, so we're more
  aligned with this pattern than the reference (which had to bolt a websocket
  round-trip onto a client-rendered app).

**Improve.**
- **Close the SSRF surface** the reference only half-guards: an explicit
  `:host-allow`/`:url` allowlist per source, validated at load, in addition to the
  param `:allow` set.
- One `:kind` multimethod for *all* sources (the reference split this across a
  pluggable datasource multimethod **and** a hard-coded `case` for hydration
  sources — unify them).

### 15.7 Imports (external-data injection / "hydration")

**Pattern.** Declarative rules that pull external/derived data into a document at
**triggers** (`on-create`, `on-open`, on named events). Only the *triggered*
injections run (lazy — no fetch unless needed). A bidirectional path-mapping
library maps source paths → document paths, supports transforms and a registered
derivation (e.g. age↔DOB), and emits **only changed `[path value]`** (idempotent).
Injections can **chain** — a later one sees an earlier one's pending changes.

**Have.** `imports.clj` with `:trigger` + `:mapping`.

**Build.** Formalize the import spec and make it lazy + diff-based + chainable:

```clojure
:imports
[{:on    #{:create}              ; :create | :open | :event/<name>
  :from  :patient                ; a source id (§15.6)
  :params {:mrn [:meta :mrn]}    ; resolved from doc/meta or a prior import's pending changes
  :map   [{:from [:name :family] :to :last-name}
          {:from [:dob]          :to :age :via :age-from-dob}]  ; named transform
  :into  :data}]                 ; :data | :meta
```

- Run only imports whose `:on` intersects the current trigger; fetch the source
  lazily; emit `[[path value]]` deltas **only where the value differs**; thread
  pending deltas so later imports can reference earlier ones.

**Improve.** Make the source type a **multimethod** (the reference hard-coded it
as a `case` — its own TODO), drop the external mapping dependency in favor of our
own small path-map + named-transform registry, and fix the "inject at document
root" case the reference disabled due to a vector-index bug (our paths are
explicit vectors, so root injection is well-defined).

### 15.8 Validation

**Pattern.** Per-field validators declared in the model
(`:required`, `:email`, `[:max-count 7]`, custom comparators), plus
**`:validate-when`** preconditions (only validate a field if other validations
pass — conditional/dependent validation), and a document-level pass that gates
submission and returns `[{path error}]`.

**Have.** Field-error **reactions** + a `:form-valid?` reaction; validity already
expressed in the reactive graph.

**Build.** We don't need a parallel validator engine — Domino reactions already
*are* the dataflow. Add:
- A small **validator vocabulary** that compiles to error reactions (so authors
  write `:validation [:required [:max-count 7]]` and we generate the reaction),
  keeping authoring declarative while reusing Domino for evaluation.
- **Conditional validation**: a field's error reaction takes a guard reaction as
  input (the `:validate-when` idea, expressed as a reaction dependency).
- A document-level `:valid?` reaction that the `submit` action (§15.5) gates on.

**Improve.** Because validity is *in the reactive graph*, error state and
visibility/enable already update live over SSE with no extra round-trip — the
reference re-runs a separate UI-rule engine and re-broadcasts; we get it for free.

### 15.9 Partials (reusable definition blocks)

**Pattern.** A "fragment" is a named, separately-stored reusable sub-tree of form
definition (e.g. a common role-picker block), referenced by id and **spliced in at
parse time**. **Weakness:** the reference splices at *write* time, so updating a
shared fragment does **not** propagate to already-saved forms (stale copies).

**Have.** None; every form is self-contained.

**Build.** Add `partials/` (EDN blocks) + a `{:include :partial-id}` node that the
form loader splices when parsing a form. Partials can carry model+events+markup
fragments (e.g. an "address" block: three fields + a validation + markup).

**Improve.** Splice at **load** time (forms are parsed fresh per version), so a
partial fix reaches every form that includes it — *or* version-pin partials with
the including form's version when we want reproducibility. Either is strictly
better than the reference's frozen write-time copy.

### 15.10 Workflow orchestration (states & actions)

**Pattern.** A workflow is **EDN data, not code**: pages + a state map + an
`:actions` map. There is **no engine loop** — when a document action arrives, the
orchestrator looks up the action, resolves any dynamic values, runs an ordered
list of **steps**, guards on the current state, and returns **directives** the
document engine applies (set-state / set-field, our names in §15.12). State
transitions are themselves a step whose transition table is *data*. **Weaknesses:** no durable
per-instance/event log (state lives only in the document), no validation that the
state graph is well-formed, and multi-step actions are fail-fast with **no
rollback/compensation**.

**Have.** `:exports` + `POST /doc/:id/action/:aid` run a templated action; no
states/transitions.

**Build.** Attach a declarative workflow to the form:

```clojure
:workflow
{:initial :draft
 :states {:draft  {:on {:submit :review}}
          :review {:on {:approve :done :reject :draft}}
          :done   {:terminal? true}}
 :actions
 {:submit  {:guard :valid?                       ; a document reaction (§15.8)
            :steps [{:do :set-state :to :review}
                    {:do :notify :to :reviewer :template :submitted}
                    {:do :snapshot :as :report}]}
  :approve {:require-role :reviewer
            :steps [{:do :set-state :to :done}
                    {:do :pdf :template :summary :store :reports}]}}}
```

- Implement actions as a **Mycelium workflow** over the existing
  `cells/document`. Each step is dispatched by a **multimethod** `run-step :do`
  (pluggable, §15.11). A step returns a **directive** (`{:set-state …}`,
  `{:set-field [path val]}`, `{:emit-report …}`) which the document layer applies
  in-process and persists + broadcasts — same shape as the reference's cross-
  service hooks, but no HTTP/JWT because it's one process.
- **Guard + state check** before running: validate the document (§15.8) and that
  the transition is legal for the current `:status`/`:workflow :state`.

**Improve.**
- **Validate the transition graph at load** (no unreachable/terminal-less states,
  every `:on` target exists) — the reference validates none of this.
- **Durable workflow-event log** (`{:doc-id :action :step :result :at :by}`),
  which the reference entirely lacks (state lived only in document metadata). This
  doubles as the §15.4 audit for transitions.
- **Idempotency + compensation** for multi-step actions: each step carries an
  idempotency key; a failed step can declare a compensating step so a partial
  run (e.g. emailed-then-pdf-failed) can be unwound or safely retried — the
  reference is fail-fast with partial side effects and no undo.

### 15.11 Pluggable step dispatcher & external clients

**Pattern.** Two open dispatch systems: **processors** (compute dynamic values in
the action config, pure) and **actions/steps** (perform side effects). Both are
plain multimethods — a new behavior is one `defmethod` + a require line, no core
change. External systems (the form engine, directory, FHIR, fax, email) are
wrapped as `clients.*` namespaces. **Weakness:** clients are *ad-hoc namespaces of
fns*, not a uniform abstraction — no shared retry/timeout/circuit-breaker, hard to
mock/swap.

**Have.** A single external `clients` component (patient stub); `exports` is a
seed of the step idea.

**Build.**
- **Step dispatcher**: `(defmulti run-step :do)` with built-ins `:set-state`,
  `:set-field`, `:notify`, `:pdf`, `:snapshot`, `:call`. Adding `:fax` is one
  `defmethod` + a require — the same open-dispatch extensibility, applied to our
  in-process step layer.
- **Value resolution in step configs**: rather than a separate templating engine,
  resolve `{:from [path]}` / `{:reaction :id}` placeholders against the document
  using our existing render context, and evaluate any inline expressions with
  **sci** (sandboxed) — we already sci-evaluate form fns, so authors get one
  consistent, *safe* expression mechanism instead of trusted `eval`.

**Improve.** Give external integrations a **uniform client protocol** (an
Integrant component implementing `fetch`/`call` with a shared timeout + retry +
circuit-breaker policy), so every integration (FHIR, directory, fax, mail) is
configured the same way and mocked the same way in tests — replacing the
reference's inconsistent per-client namespaces. Keep code-as-config **sci-only**
(never `clojure.core/eval`), closing the reference's biggest safety hole.

### 15.12 Directive contract (in-process replacement for cross-service hooks)

The reference's orchestrator returns *directives* that the document engine
applies (rather than writing the store itself), keeping the orchestrator
credential-free. We keep the **same pure-step / applied-directive split** but in
one process:

- A step returns one of `{:set-state s}`, `{:set-field [path v]}`,
  `{:set-meta [path v]}`, `{:emit-report …}`, `{:notify …}` (or a vector of them).
- A single `apply-directives!` in the document layer applies them through the
  session (so they recompute reactions, persist, audit, and broadcast over SSE
  like any other change).
- This keeps steps **pure and unit-testable** (input doc + step → directives) with
  no I/O in the dispatch itself, while side effects (mail/pdf/http) live in the
  clients (§15.11) behind the resilience policy.

### 15.13 Pages, index lookups & production hardening

**Pattern.** A workflow declares **pages** (`:page-type` index / documents /
document-search); an **index** multimethod resolves an external key (e.g. a
patient identifier) into an entity bundle used to *prepopulate and search*
documents — the read-side mirror of the write-side processor system.

**Have.** Landing list + editor + SSE; imports can prepopulate.

**Build (Phase 11).**
- `:pages` on a form/workflow with `:page-type #{:index :documents :search}`;
  routes derive from them; the document list filters by `:status`/owner/metadata.
- An **index resolver** multimethod (`resolve-index :kind`) that turns a lookup
  key into `{:index … :entities {…}}`, feeding both document creation (prepopulate
  via §15.7 imports) and metadata search.
- **Backends**: implement the store abstractions over a real query DB
  (XTDB/Datalevin) — the target schema (§15.2) is already DB-agnostic. Add PDF
  generation (`:pdf` step), a FHIR/external client (§15.11), and OAuth2/OIDC
  alongside the existing bcrypt/session auth.

### 15.14 Net improvements over the reference systems

| Area | Reference weakness | Stepvine plan |
|---|---|---|
| Published-version immutability | Convention only; in-place update possible | Write-once versions + content `:digest` (§15.1) |
| Document→version data migration | None | Opt-in audited **rebase** with field-mapping (§15.1) |
| Concurrency backstop | No revision token; last-write-wins | Monotonic `:rev` + stale-base rejection (§15.2–15.3) |
| Failure handling | `System/exit` on DB/audit error | Per-client error + dead-letter, never exit (§15.3) |
| Audit | No diffs, no per-doc index, transitions unaudited | Before/after diffs, per-doc index, transitions audited (§15.4) |
| Post-submit editing | Document stays editable | Hard read-only on submitted views (§15.5) |
| Data-source SSRF | Params allowlisted, host/path not | Host+path allowlist per source (§15.6) |
| Source pluggability | Split multimethod + hard-coded `case` | One `:kind` multimethod for all sources/imports (§15.6–15.7) |
| Code-as-config safety | Full `clojure.core/eval` | **sci** sandbox everywhere (§15.11) |
| Reusable blocks | Frozen at write time (stale) | Spliced at load / version-pinned partials (§15.9) |
| Workflow graph | Unvalidated, scattered transitions | Validated transition table, declared on the form (§15.10) |
| Workflow durability | No instance/event log | Durable workflow-event log = transition audit (§15.10) |
| Multi-step actions | Fail-fast, no rollback | Idempotency keys + compensation (§15.10) |
| External clients | Ad-hoc fn namespaces | Uniform client protocol w/ retry+timeout+breaker (§15.11) |
| Service topology | Two services + JWT handoff + 409s | Single process; directives applied in-process (§15.12) |

### 15.15 Suggested build order

1. **§15.1–15.2** versioned form store + pinned document record (foundation).
2. **§15.4** audit store (everything below writes to it).
3. **§15.3** durability hardening (`:rev`, no-exit, lock/presence stores).
4. **§15.8 + §15.5** validation vocabulary → submission/approval + post-submit
   read-only.
5. **§15.6–15.7** unify sources + imports under one resolver.
6. **§15.9** partials.
7. **§15.10–15.12** workflow states/actions/steps + directive layer + client
   protocol.
8. **§15.13** pages, index lookups, query-DB backends, PDF, OIDC.

Each step lands behind the existing store/cell abstractions with its own tests and
a storyboard scenario, so the system stays green throughout.
