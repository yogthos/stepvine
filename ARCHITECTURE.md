# Stepvine — Architecture

> A server-authoritative platform for building, hosting, and concurrently editing
> data-entry forms. The browser is a dumb terminal (Datastar SSE); all form logic,
> reactive computation, validation, and side effects run on the server. The form
> **document** is a Domino schema — the single source of truth.

Stepvine synthesises three ideas into a single, modern
foundation:

| Role | What Stepvine adopts |
|------|---------------------|
| Form/document editor | Form definitions (model/views/rules/reports), multi-user editing, field locking, business rules, validation, workflow actions |
| Workflow engine | Document lifecycle (states + transitions), action dispatch, index-based document search, page-based navigation, templated config (weaver) |
| Widget library | Widget taxonomy, application-context pattern (get/save/lock/validate/visible?), table operations, typeahead datasources |

Stepvine uses **Domino** (a reactive data-graph
engine) and **Mycelium** (a compiled-cell workflow engine) to manage forms and documents. Where Ibis+Aviary
The entire frontend is a dumb terminal using **Datastar** — the server renders HTML and pushes patches over SSE; the browser has zero business logic.

---

## 1. The Three Pillars

| Layer | Library | Responsibility | Scope |
|-------|---------|----------------|-------|
| **Reactive state** | **Domino** | The form's data graph: model paths, events (cascading derived fields), effects (side-effect boundaries), reactions (derived display values). | Per **document/session** |
| **Orchestration** | **Mycelium** | Each HTTP route is a compiled workflow of pure `defcell`s: parse → resolve → transact → diff → render → respond. | Per **HTTP request** |
| **Transport / UI** | **Datastar** | `data-*` attributes bind DOM to server-owned signals; user events POST intent; server pushes `PatchElements` + `PatchSignals` over SSE. | Per **connection** |

```
Browser (Datastar, dumb terminal)
   │  data-on:input → @post('/doc/:id/field/:fid')       (user intent)
   ▼
Mycelium workflow-handler
   │  cells: parse → load-session → transact → diff → render → respond
   ▼
Domino engine (editor)
   │  events recompute derived fields (BMI), reactions recompute display values
   ▼
Diff old vs new signals
   ▼
Datastar SSE: patch-signals! (changed field/reaction/lock values)
   ▼
Browser DOM updates in place
```

---

## 2. Concept Map: Documents/Forms/Widgets → Stepvine

### 2.1 Form Definition

| Document manager concept | Stepvine equivalent | Status |
|---|---|---|
| `:model` with `:path` | Domino `:model` with `:id` | ✅ done |
| `:validation` per field | Domino `:reactions` (field-error reactions) | ✅ done |
| `:validate-when` preconditions | Reaction over reaction (e.g. `:form-valid?` over `:weight-error :height-error`) | ✅ done |
| `:business-rules` (action) | Domino `:events` (input→output cascade) | ✅ done |
| `:business-rules` (trigger-action) | Not yet; trigger-action maps to Domino effects | 🔜 planned |
| `:business-rules` (row-action) | Domino subcontext events (per-collection-item) | ✅ done |
| `:business-rules` (table-action) | Collection add/remove triggers recompute via events | ✅ done |
| `:functions` | Inline `:handler` fns (or sci-evaluated named fns) | ✅ done |
| `:ui-rules` (visibility, required, errors) | Domino `:reactions` + Datastar `data-show`/`data-attr` | ✅ done |
| `:reports` (hiccup templates) | `:exports` with placeholder substitution | ✅ done |
| `:views` | `:views` with `:markup` (hiccup + widget keywords) | ✅ done |
| `:metadata` | Top-level `:id`, `:title`, `:version` on form | ✅ done |
| `:datasources` (external lookups) | `:imports` with `:trigger` + `:mapping` | ✅ done |
| `:hydrations` | Covered by `:imports` + `:options` sources | ✅ done |

### 2.2 Workflow / Document Lifecycle

| Form manager concept | Stepvine equivalent | Status |
|---|---|---|
| Workflow metadata + listing | Form `:id` + `:title`; document list page | ✅ done |
| Pages (index, documents) | Routes: `/` (landing), `/doc/:id` (editor), `/doc/:id/sse` (stream) | ✅ done |
| Index search (patient by MRN) | `:imports` with `:trigger` field + patient-client | ✅ done |
| Document create | `POST /form/:id/new` → `documents/create!` | ✅ done |
| Document open (iframe) | `GET /doc/:id` → full-page server render + SSE stream | ✅ done |
| Document actions | `POST /doc/:id/action/:aid` → exports | ✅ done (exports) |
| `:document-state-change` action | Not yet; document `:state` transitions | 🔜 planned |
| `:email` action | Not yet; Mycelium effect cell that calls postal | 🔜 planned |
| `:save-pdf-report` action | Not yet | 🔜 planned |
| Templating (weaver) | Selmer for the page shell; Domino signals for field values | ✅ done |
| Multi-stage workflow (states) | Not yet; `:state` key on document + allowed-transitions | 🔜 planned |

### 2.3 Widget System

| Widgets concept | Stepvine equivalent | Status |
|---|---|---|
| `render-widget` + context | `render/render-widget` multimethod dispatching on resolved widget keyword | ✅ done |
| `:get` / `:save` (context) | Replaced by Datastar `data-bind` + `data-on:input` POST; server owns all state | ✅ done |
| `:lock` / `:unlock` | `POST /doc/:id/field/:fid/lock` / `/unlock`; lock state broadcast as Datastar signal | ✅ done |
| `:locked-from-us?` | `locks` signal map per field; inputs disable when locked by another user | ✅ done |
| `:read-only?` | `:read-only` attr on field def → rendered as `readonly` attribute | ✅ done |
| `:ready?` / `:set-ready` / `:set-not-ready` | Implicit: the Datastar callback fires on SSE receipt; field is "ready" after server ack | ✅ implicit |
| `:validate` / `:validation-errors` | Reaction-valued `:error` attr on field → rendered error text | ✅ done |
| `:visible?` | Reactions producing display values bound to `data-show` | ✅ done |
| `:in-use-by` | `locks` signal → per-field locker uid displayed | ✅ done |
| Table add/remove/move/clear | Collection widget: `add-item!` / `remove-item!` with element-patch re-render | ✅ done |
| Widget taxonomy (textbox, dropdown, checkbox, date-picker, etc.) | `render-widget` multimethod; currently: `form`, `input-field`, `dropdown-select`, `show`, `action`, `collection`, `build-button`, `value` | ✅ core done |
| `:datasource` typeahead | `:imports` trigger mechanism + `:options {:source ...}` for dropdowns | ✅ done |
| styling | Server-rendered HTML with minimal custom CSS (see `form.html`) | ✅ done |

---

## 3. Form Document — Single Source of Truth

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
  [{:id :overweight? :args [:bmi] :fn (fn [bmi] (>= (or bmi 0) 25))}
   {:id :bmi-category :args [:bmi] :fn (fn [b] (cond (< b 18.5) "underweight" ...))}]}

 :imports
 {:patient {:trigger :patient-id :mapping {:fname [:given] :lname [:family]}}}

 :exports
 {:summary {:label "Export" :template {:weightKg :kg :bmi :bmi :category :bmi-category}}}

 :views
 {:default
  {:title "BMI Calculator"
   :markup
   [:c/form {}
    [:c/input-field {:label "Weight (kg)" :id :kg}]
    [:c/input-field {:label "Height (m)"  :id :m}]
    [:c/input-field {:label "BMI" :id :bmi :read-only true}]
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
  owns state — it references field/reaction ids.
- **`:options {:source ...}`** on a field def references a named option set
  (DB-sourced dropdowns), resolved at render time.

### 3.1 Collections (Domino subcontexts)

A field with `:collection? true` holds a map of items, each with its own schema
(model + events + reactions). Per-item derived fields recompute independently:

```clojure
[:members
 {:id :members :collection? true :index-id :mid
  :schema
  {:model [[:first {:id :first}] [:last {:id :last}] [:full {:id :full}]]
   :events [{:inputs [:first :last] :outputs [:full]
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
| `GET /` | List user's documents | HTML (Selmer shell + hiccup list) |
| `GET /doc/:id` | Ensure session → render full page + seed signals + open SSE | HTML + data-signals |
| `GET /doc/:id/sse` | Open SSE stream, register in hub, sync initial signals | SSE (long-lived) |
| `POST /doc/:id/field/:fid` | Parse value → coerce → transact (lock-aware) → broadcast | SSE patches (204 to caller) |
| `POST /doc/:id/field/:fid/lock` | Acquire lock → broadcast lock state | 204 |
| `POST /doc/:id/field/:fid/unlock` | Release lock → broadcast lock state | 204 |
| `POST /doc/:id/coll/:coll/add` | Add item → recompute → re-render collection → broadcast | SSE patches |
| `POST /doc/:id/coll/:coll/:idx/remove` | Remove item → recompute → re-render → broadcast | SSE patches |
| `POST /doc/:id/coll/:coll/:idx/field/:fid` | Parse → coerce → transact (lock-aware) → broadcast | SSE patches |
| `POST /doc/:id/action/:aid` | Run export → render result → broadcast | SSE patches |
| `POST /form/:id/new` | Create document → redirect | 302 |

### 4.3 Session Model

`procyon-editor` is reused wholesale — it's already server-side and
frontend-agnostic (`.cljc`). Key functions:

- `impl/create-session` — initialize a Domino context from a form + initial db
- `impl/apply-changes` — transact field changes, triggering Domino events/reactions
- `impl/value` — read current value of a field or reaction
- `impl/db` — the raw Domino db
- `e/lock!` / `e/unlock!` / `e/save-ids!` — multi-user field locking
- `e/disconnect!` — release all locks on disconnect

The session manager's `:on-update` hook (a) persists the Domino db to the
document store and (b) diffs old vs new signals and broadcasts changed ones over
the Datastar hub. This is the reactive multi-user loop.

---

## 5. Server-Side Widget Rendering

Stepvine replaces Procyon's client-side Reagent/antd renderer with a
**server-side multimethod** (`render/render-widget`) that emits HTML with
Datastar `data-*` bindings.

### 5.1 Widget Taxonomy (current)

| Widget keyword | Renders | Key bindings |
|---|---|---|
| `:c/form` | `<div>` with `data-signals` seed + `data-init` SSE open | Seeds all field/reaction/collection signals |
| `:c/input-field` | `<div.field>` with `<label>` + `<input>` | `data-bind` + `data-on:input` POST; `readonly` if locked or `:read-only`; `data-attr-disabled` if locked-by-other; error text from reaction |
| `:c/dropdown-select` | `<div.field>` with `<label>` + `<select>` + `<option>`s | Same bindings as input-field; options from `:options {:source ...}` or static `:values` |
| `:c/show` | `<div>` with `data-show` bound to a reaction | Content only visible when reaction is truthy |
| `:c/action` | `<button>` that POSTs to `/doc/:id/action/:aid` | Triggers export; result patched into target element |
| `:c/collection` | Container `<div>` with per-item template | Each item has scoped signals `<coll>_<idx>_<field>`; add/remove buttons POST |
| `:c/build-button` | `<button>` (form builder only) | POSTs to `/doc/:id/build` |
| `:procyon.util/value` | Inline `<span>` with `data-text` bound to a field/reaction value | Renders derived values inline |

### 5.2 Render Context

Built from the live session by `render/session->context`:

```clojure
{:values      {field-id -> current-value}     ;; top-level fields
 :rxns        {reaction-id -> current-value}   ;; reactive display values
 :field-opts  {field-id -> {:type ... :required? ...}}
 :collections {coll-id -> {:order [idx...] :field-opts {} :items {idx {fid val}}}}
 :item        {:coll :members :idx "abc123"}   ;; set when inside a collection
 :aliases     {"c" "procyon.components"}       ;; widget namespace expansion
 :doc-id      "uuid..."}
```

### 5.3 Signal Naming

- Top-level field `:kg` → signal `$kg`
- Reaction `:overweight?` → signal `$overweight_` (non-alphanumeric chars collapsed)
- Collection item field `:members` / `"abc"` / `:first` → signal `$members_abc_first`

---

## 6. Multi-User Model

### 6.1 Field Locking

Locks are per-field (and per-collection-item-field). Flow:

1. User focuses an input → `data-on:focus` POSTs `/lock`
2. Server acquires lock for `uid` on `field-id`; broadcasts `locks` signal map
3. Other clients see `locks.kg = "other-user-id"` → input gets `disabled`
4. User blurs → POSTs `/unlock`; server releases; broadcasts updated locks
5. On SSE disconnect, all of that client's locks are released

Lock state is a flat signal map `{"kg" "uid-1", "members_abc_first" "uid-2", ...}`.
Signals are patched whole on each change (JSON merge-patch: `nil` clears a lock).

### 6.2 Presence

The `presence` signal tracks the number of distinct users connected to a document.
Broadcast on connect/disconnect.

### 6.3 Consistency

- Lock-aware saves: if another user holds the lock, `apply-field-as!` returns
  `:rejected` and no change is transacted.
- Lock-free saves: if no lock exists (debounce/blur race), apply anyway.
- The session manager serializes all changes through a single atom swap; two
  concurrent writes to the same field are ordered.

---

## 7. Data Relationships

Stepvine handles four kinds of data relationships, all declared in the form
definition (from `PLAN.md` §6):

### 7.1 Derived Fields (Domino events)
BMI recalculates when weight or height changes. Declared once; zero view code.

### 7.2 External Service Imports
When `:patient-id` changes, fetch from patient-client and transact mapped fields
(`:fname`, `:lname`, `:dob`) into the document. Recompute + broadcast.

### 7.3 DB-Sourced Dropdown Options
`:clinic` field declares `:options {:source :clinics/active}`. Options resolved
at render time from the options store. Swappable to a real DB query.

### 7.4 Cross-Field Validation (reactions over reactions)
`(and (nil? weight-error) (nil? height-error))` → `form-valid?` reaction. A
reaction can depend on other reactions, building validation chains.

---

## 8. Document Access Control

```
Owner (created-by) ─── can edit, share, delete
Shared users        ─── can edit (access via /doc/:id)
Anonymous           ─── no access (redirected to /login)
```

- `documents/can-access?` gates every `/doc/:id` route via `wrap-doc-access`
- `documents/share!` adds a user to the `:shared` set
- Auth: bcrypt-hashed passwords, ring session, CSRF protection

---

## 9. Phased Implementation Plan

### Phase 1 — Skeleton + Static Render ✅
- Kit skeleton, EDN form definitions, Selmer page shell
- `:store/forms`, `:store/documents` (atom)
- Server-rendered form with Datastar bindings (`data-bind` + `data-on:input`)
- Static BMI calculator form

### Phase 2 — Reactive Server ✅
- Domino engine in session
- Mycelium cells for field updates
- `data-signals` seed + `data-init` SSE open
- `patch-signals!` broadcast → DOM updates in place
- BMI recalculates live

### Phase 3 — Multi-User ✅
- Datastar hub (per-document fan-out)
- Field locking (acquire/release/broadcast)
- Presence tracking
- Lock-aware field save (reject if locked by other)

### Phase 4 — Data Relationships ✅
- External imports (`:imports` → patient-client stub)
- DB-sourced dropdowns (`:options {:source ...}`)
- Collections (Domino subcontexts) with per-item derived fields
- Cross-field validation (reactions over reactions)

### Phase 5 — Mycelium Refactor ✅
- All form routes as Mycelium workflows (parse → apply → broadcast)
- `cells/form.clj` + `workflows/form.clj` + `workflows/document.clj`
- Collection add/remove/field cells
- Lock/unlock cells

### Phase 6 — Form Builder + Exports ✅
- Visual form builder (itself a Stepvine form)
- `build-form!` generates EDN and saves via `forms/save-form!`
- Templated exports (`:exports` → placeholder substitution)
- Migrations (`:version` + `:migrations` fns for schema evolution)

### Phase 7 — Workflow State Machine 🔜 planned
- Document `:state` field (draft → submitted → completed → cancelled)
- `:allowed-transitions` on form metadata
- Action steps: state-change, email, save-pdf-report, fax
- State filtering on document list / search
- Audit log of state transitions

### Phase 8 — Pages & Workflow Navigation 🔜 planned
- Aviary-style page definitions on form: `:pages` with `:page-type` (index, documents, document-search)
- Index-based routing (patient → documents)
- Custom search forms for document metadata
- Creation modals with index selection

### Phase 9 — Production Hardening 🔜 planned
- Real database backends (XTDB/Datalevin behind store abstractions)
- FHIR client for imports/exports
- Resilience (SSE reconnect, session recovery)
- Auth (OAuth2/OIDC)
- PDF report generation
- Audit logging

---

## 10. Code Inventory

### Reused from Procyon (zero new code)
- `procyon.editor` — `session-manager`, `create-session!`, `swap-session!`, `lock!`/`unlock!`, `save-ids!`, `disconnect!`
- `procyon.editor.impl` — sci evaluation, `create-session`, `apply-changes`, `value`, `db`
- `procyon.editor.locks` — multi-user field lock manager
- `procyon.editor.data` — `initialize-ctx`, `transact-ctx`, Domino wrapper

### Net-new code written for Stepvine

| Namespace | Role |
|-----------|------|
| `yogthos.stepvine.core` | System init, Integrant graph |
| `yogthos.stepvine.config` | Profile-based config loading |
| `yogthos.stepvine.session` | Session manager component wrapping `procyon.editor`; on-update persistence + broadcast |
| `yogthos.stepvine.render` | Server-side widget renderer: multimethod dispatch, signal naming, markup walk, collection rendering |
| `yogthos.stepvine.hub` | Datastar connection hub: register/unregister/broadcast/heartbeat |
| `yogthos.stepvine.forms` | Form definition store (disk-backed EDN) |
| `yogthos.stepvine.documents` | Document instance store (duratom/atom), access control |
| `yogthos.stepvine.options` | Named option-set store for DB-sourced dropdowns |
| `yogthos.stepvine.imports` | External-service import helpers |
| `yogthos.stepvine.exports` | Templated export substitution |
| `yogthos.stepvine.migrations` | Form versioning + document db migration |
| `yogthos.stepvine.users` | User account store (duratom/atom, bcrypt auth) |
| `yogthos.stepvine.clients` | External service client component (patient stub) |
| `yogthos.stepvine.builder` | Form builder: `build-form` from builder document data |
| `yogthos.stepvine.docs` | Document session lifecycle: `ensure!` + `form-raw-for` |
| `yogthos.stepvine.cells.form` | Mycelium cells for field/lock/collection operations |
| `yogthos.stepvine.cells.document` | Mycelium cells for document list/create/share/delete/export |
| `yogthos.stepvine.workflows.form` | High-level workflow compilations for form routes |
| `yogthos.stepvine.workflows.document` | High-level workflow compilations for document routes |
| `yogthos.stepvine.web.server` | Jetty server component |
| `yogthos.stepvine.web.handler` | Ring handler with middleware stack |
| `yogthos.stepvine.web.routes.pages` | Page routes (HTML + Datastar endpoints) with auth + CSRF |
| `yogthos.stepvine.web.routes.api` | API routes |
| `yogthos.stepvine.web.routes.utils` | Route helper utilities |
| `yogthos.stepvine.web.sse` | SSE stream handler (Datastar adapter) |
| `yogthos.stepvine.web.auth` | Login/register/logout handlers |
| `yogthos.stepvine.web.security` | Auth middleware, CSRF, document access control |
| `yogthos.stepvine.web.middleware.core` | Ring middleware utilities |
| `yogthos.stepvine.web.middleware.formats` | Content-type negotiation |
| `yogthos.stepvine.web.middleware.exception` | Exception handling middleware |
| `yogthos.stepvine.web.controllers.health` | Health check endpoint |

### Configuration / Data Files

| File | Purpose |
|------|---------|
| `resources/system.edn` | Integrant system map (components, routes, env vars) |
| `resources/html/form.html` | Selmer page shell with Datastar CDN + minimal CSS |
| `forms/bmi.edn` | BMI calculator fixture (Phase 1) |
| `forms/intake.edn` | Patient intake fixture (Phase 4: imports + options + cross-field validation) |
| `forms/builder.edn` | Form builder fixture (Phase 6: meta-form, collections) |
| `forms/roster.edn` | Team roster fixture (Phase 4b: collections with per-item events/reactions) |
| `options/clinics.edn` | Clinic reference data (DB-sourced dropdown) |
| `options/field-types.edn` | Field type reference data (form builder dropdown) |
| `data/documents.edn` | Persisted document instances (duratom) |
| `data/users.edn` | Persisted user accounts (duratom) |

---

## 11. Design Principles

1. **Server is authoritative.** No business logic in the browser. The DOM is a
   projection of server state; the client only reports user intent and renders
   patches it is handed.
2. **The Domino document *is* the schema.** A form's `:model` / `:events` /
   `:reactions` define data shape, derived fields, cascades, and display
   boundaries. Everything else (views, imports, exports) hangs off it.
3. **Reuse over rebuild.** Procyon's server-side Domino/session/compile/transactor
   layer is lifted wholesale. Net-new code is only for the server-side HTML
   renderer and Datastar transport.
4. **One mechanism per concern.** Domino = per-document reactive state. Mycelium =
   per-request orchestration. Datastar = transport + DOM binding. They don't
   overlap.
5. **Declarative data relationships.** Derived fields, imports, dropdown options,
   and cross-field validation are all expressed in the form EDN, not hand-wired
   in handler code.
6. **Backend-swappable stores.** Every store (`:store/forms`, `:store/documents`,
   etc.) exposes a small API. Disk-backed atoms today; XTDB/Datalevin tomorrow
   behind the same API.
