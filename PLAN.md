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

---

## 2. The Three Pillars

| Layer | Library | Responsibility | Scope |
|-------|---------|----------------|-------|
| **Reactive state** | **Domino** (`domino/core 0.4.0-alpha.3`) | The form's data graph: model paths, events (cascading derived fields), effects (side-effect boundaries), reactions (derived display values). | Per **document/session** |
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

### 3.1 Collections (Domino subcontexts)

A field with `:collection? true` holds a map of items, each with its own schema
(model + events + reactions). Per-item derived fields recompute independently:

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
5. **Domino pinned to `0.4.0-alpha.3`.** The vendored editor targets that API
   surface (`get-downstream`/`select`/`set-value`/…). Domino `0.4.0` stable is a
   ground-up rewrite exposing only `transact`/`initialize`/`trigger-effects`, so
   upgrading would require rewriting the editor layer. One cosmetic `random-uuid`
   compile warning from `domino.util` remains (external alpha namespace); the
   warning in our own `editor.util` is fixed via an unconditional
   `(:refer-clojure :exclude [random-uuid])`.

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

**Phase 7 — Workflow state machine.** Document `:state` (draft → submitted →
completed → cancelled); `:allowed-transitions` on form metadata; action steps
(state-change, email, save-pdf, fax); state filtering on the document list; audit
log of transitions.

**Phase 8 — Pages & workflow navigation.** Page definitions on a form (`:pages`
with `:page-type` index/documents/document-search); index-based routing; custom
search forms for document metadata; creation modals with index selection.

**Phase 9 — Production hardening.** Real database backends (XTDB/Datalevin behind
the store abstractions); FHIR client for imports/exports; session recovery; OAuth2/OIDC;
PDF report generation; audit logging.

---

## 12. Code Inventory

### Vendored editor layer (`yogthos.stepvine.editor.*`)

The Domino session/editor layer (originally adapted from a Reagent/Sente form
editor) is vendored under `src/clj/yogthos/stepvine/editor*` — a self-contained
`.cljc` lib depending only on `domino` + `sci`:

- `editor` — `session-manager`, `create-session!`, `lock!`/`unlock!`, `save-ids!`, `disconnect!`
- `editor.impl` — sci evaluation, `create-session`, `apply-changes`, `value`, `db`
- `editor.locks` — multi-user field lock manager (related-field locking)
- `editor.data` — `initialize-ctx`, `transact-ctx`, Domino wrapper
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
