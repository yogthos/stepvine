# Stepvine — Backend-Driven Form Builder

> A server-authoritative engine for building complex, reactive data-entry forms.
> The browser is a **dumb terminal** (Datastar); all form logic, derived values,
> validation, and side effects run on the server. The **form document is a
> Domino schema** — the single source of truth that data dependencies, events,
> and effects attach to. Request orchestration is a **Mycelium** workflow.
> Wherever possible we **reuse Procyon's server-side form/session/domino layer**
> and replace only its Reagent/Sente frontend.

---

## 1. Goals & Principles

- **Server is authoritative.** No business logic in the browser. The DOM is a
  projection of server state; the client only reports user intent and renders
  patches it is handed.
- **The Domino document *is* the schema.** A form's `:model` / `:events` /
  `:effects` / `:reactions` define data shape, derived fields, cascades, and
  side-effect boundaries. Everything else (views, imports, exports) hangs off it.
- **Complex data relationships are first-class.** Derived fields (BMI ←
  height·weight), cross-field cascades, external service lookups, and
  DB-sourced option lists are all expressed declaratively in the document, not
  hand-wired.
- **Reuse over rebuild.** Procyon already has a battle-tested server-side
  domino/session/compile/transactor layer (`.cljc`, frontend-agnostic). We lift
  it. We write net-new code only for the **server-side HTML renderer** and the
  **Datastar SSE transport**.
- **One mechanism per concern.** Domino = per-document reactive state. Mycelium
  = per-request orchestration. Datastar = transport + DOM binding.

---

## 2. The Three Pillars & How They Compose

| Layer | Library | Responsibility | Scope |
|-------|---------|----------------|-------|
| **Reactive state** | **Domino** (`domino/core 0.4.0-alpha.3`) | The form's data graph: model paths, events (cascading derived fields), effects (side-effect boundaries), reactions (derived display values). | Per **document/session** |
| **Orchestration** | **Mycelium** (already in deps) | Each HTTP route is a compiled workflow of pure `defcell`s: parse request → resolve session → apply change via Domino → diff → render fragments → emit response. Resources (session store, DB, service clients) injected per request. | Per **HTTP request** |
| **Transport / UI binding** | **Datastar** (`dev.data-star.clojure/sdk` + an adapter) | `data-*` attributes bind DOM to server-owned **signals**; user events POST intent; server pushes **PatchElements** (HTML fragments) and **PatchSignals** (state) over SSE. | Per **connection** |

**Mental model:**

```
Browser (Datastar, dumb terminal)
   │  data-on:input → @post('/form/:id/field/:fid')      (user intent)
   ▼
Mycelium workflow-handler  (mycelium.middleware/workflow-handler)
   │  cells: parse → load-session → transact → diff → render → respond
   ▼
Domino engine for this document   (procyon-editor: initialize-ctx / transact-ctx)
   │  events recompute derived fields (BMI), reactions recompute display values
   ▼
Diff old vs new (db + reactions)  (procyon session_manager: editscript)
   ▼
Datastar SSE: patch-elements! (changed fields/sections) + patch-signals! (rxns)
   ▼
Browser DOM updates in place
```

Domino and Mycelium are **complementary, not competing**: Mycelium handles the
*request*, Domino handles the *document*. A Mycelium cell calls into the Domino
session; Domino's outgoing effects (e.g. "publish to FHIR", "fetch from
service") can themselves be implemented as Mycelium cells/resources.

---

## 3. The Form Document — Single Source of Truth

We adopt Procyon's proven document shape (see `procyon/demo/v6.edn`). A form is
EDN (with embedded Clojure fns, evaluated safely via **sci**):

```clojure
{:data        ; <-- the Domino schema. This is the heart.
 {:model      [[:patient
                [:weight {:id :kg, :type :number}]
                [:height {:id :m,  :type :number}]
                [:bmi    {:id :bmi, :type :number}]]
               [:id {:id :patient-id}]]
  :events     [{:id :calc-bmi
                :inputs  [:kg :m]
                :outputs [:bmi]
                :handler (fn [{{:keys [kg m]} :inputs}]
                           {:bmi ((fnil / 0 1 1) kg m m)})}]
  :reactions  [{:id :overweight? :args [:bmi] :fn (fn [bmi] (>= bmi 25))}]}

 :imports     ; external-service hydration (pull data IN)
 {:patient {:type :fhir
            :opts {:resource-type "Patient" :id :patient-id}
            :mapping {:mrn [:identifier 0 :value], :fname [:name 0 :given 0]}}}

 :exports     ; templated output (push data OUT) — e.g. FHIR resources
 {:bmi-observations {:templates [ ... ]}}

 :views       ; presentation. Hiccup markup with widget keywords + rxn bindings.
 {:default {:title "BMI"
            :markup [:c/form {}
                     [:c/input-field {:label "Weight (kg)" :id :kg}]
                     [:c/input-field {:label "Height (m)"  :id :m}]
                     [:c/input-field {:label "BMI" :id :bmi :read-only true}]
                     [:div {:data-show "$overweight?"} "Patient is overweight."]]}}}
```

Key properties:
- **`:data` is a Domino schema** — the user's explicit requirement. All logic and
  effects attach here.
- **Derived fields** = Domino `:events` (DAG-ordered cascade). BMI recalculates
  automatically when `:kg` or `:m` change — no view code needed.
- **Display logic** = Domino `:reactions` (Procyon's extension on top of Domino;
  pure derived values for the UI). These become **Datastar signals**.
- **External dependencies** = `:imports` (incoming effects) and a new
  **`:options`/`:sources`** concept for DB/service-backed dropdowns (§6).
- **Views are pure projection.** A view never owns state; it references field
  `:id`s and reaction ids. Multiple views over one document are free.

---

## 4. Runtime Architecture

### 4.1 Components (Integrant, in `resources/system.edn`)

Building on the existing Kit skeleton (`:server/http` → `:handler/ring` →
`:router/core`), add:

- `:store/forms` — form **definitions** (the templates above). Start in-memory
  (EDN files / atom), pluggable to a DB later.
- `:store/documents` — saved **document instances** (a form + its data). Start
  with an atom + `duratom` for crash recovery (per Procyon NOTES); XTDB/datalevin
  later if we need query/search.
- `:session/manager` — live editing sessions (Domino ctx + locks + connections),
  lifted from `procyon-editor`.
- `:datastar/hub` — registry of open SSE generators per document, for broadcast.
- `:clients/*` — external service clients (e.g. FHIR) as Mycelium resources.

### 4.2 Request lifecycle (every route is a Mycelium workflow)

Routes use `mycelium.middleware/workflow-handler` (confirmed API):
`{:resources (fn [req] …)  :input-fn (fn [req] {:http-request req})  :output-fn …}`.

Endpoints (hypermedia, not JSON):

| Route | Workflow | Output |
|-------|----------|--------|
| `GET /form/:id` | render full page shell + initial view + initial signals | HTML (Selmer shell + rendered view) |
| `GET /form/:id/sse` | open SSE stream, register generator in hub | SSE (long-lived) |
| `POST /form/:id/field/:fid` | parse value → `transact` → diff → broadcast patches | SSE patches (204 to caller) |
| `POST /form/:id/action/:aid` | trigger Domino effect / export | SSE patches |
| `POST /form/:id` (new) | create document, run `:on-create` + `:imports` | redirect / HTML |

Each is a small pipeline of `defcell`s. Example (`field-update`):

```clojure
(myc/defcell :form/parse-field-change
  {:input {:http-request :map} :output {:doc-id :any :field-id :keyword :value :any}}
  (fn [_ {req :http-request}] (parse-datastar-signals req)))

(myc/defcell :form/apply-change
  {:requires [:session-manager]
   :input {:doc-id :any :field-id :keyword :value :any}
   :output {:patches :any}}
  (fn [{:keys [session-manager]} {:keys [doc-id field-id value]}]
    ;; reuse procyon-editor: lock → transact (domino recompute) → diff
    (let [{:keys [old new]} (sm/save-and-diff session-manager doc-id field-id value)]
      {:patches (diff->datastar-patches old new)})))

(myc/defcell :form/broadcast
  {:requires [:datastar-hub] :input {:patches :any} :output {:status :int}}
  (fn [{:keys [datastar-hub]} {:keys [patches]}]
    (hub/broadcast! datastar-hub patches) {:status 204}))
```

### 4.3 Session model (lifted from `procyon-editor`)

`procyon-editor` is **already server-side and frontend-agnostic** — this is the
biggest reuse win. Directly usable:

- `editor/data.cljc` — `initialize-ctx`, `transact-ctx`, `get-db`, `get-value`,
  `get-field-opts-fn` (Domino wrapper).
- `editor.cljc` — `session-manager`, `create-session!`, `swap-session!`,
  `save-ids!`, `lock!`/`unlock!`, `connect!`.
- `editor/impl.cljc` — sci evaluation of form code + session construction.
- `editor/locks.cljc` — multi-user field locking (defer to a later phase).

From `apps/procyon` (`.cljc`, mostly transport-agnostic):
- `form/compile.cljc` — `handle-domino-pre-initialize`, `compile-form-parsed`
  (snippet/subform expansion, reaction walking, `id->path`).
- `session_manager.cljc` — `session->data` (`{:db :rxns :locks …}`),
  `on-session-update` (**editscript diff + broadcast** — exactly the hook we wire
  to Datastar), `rx-in-ctx`, `subctx-tree`.
- `transactor.cljc` — event routing (field-update / lock / unlock /
  trigger-effect). Reuse the *dispatch logic*; swap the Sente send for Datastar.

---

## 5. Server-Side Rendering — the main net-new piece

Procyon renders widgets **client-side** with Reagent + Ant Design
(`procyon-components`). We are replacing the entire frontend, so we **keep the
widget taxonomy and the markup-walking design, but write a new server renderer**
that emits HTML + Datastar attributes instead of Reagent components.

- **Reuse the concept**: `widget-dispatch` on `:component`, the `sub-form`
  prewalk over `:markup`, the `widget-map` shape (`:id`, `:field-definition`,
  `:state`, `:args`), field definitions (`:type`, `:values`, `:required?`).
- **Rewrite the implementation** as a `defmulti render-widget` (Clojure, →
  Hiccup → HTML string) where each widget emits Datastar bindings:

```clojure
(defmethod render-widget :c/input-field [{:keys [id field-definition value]}]
  [:div.field
   [:label (:label field-definition)]
   [:input {:type (input-type (:type field-definition))
            :value value
            :id (name id)
            ;; user intent → server; debounced
            :data-on:input__debounce.300ms
            (str "@post('/form/" *doc-id* "/field/" (name id) "')")
            ;; field is bound to a server-owned signal
            :data-bind (str "$" (name id))}]])
```

- **Reactions → signals.** Each Domino reaction id becomes a Datastar signal
  (`$overweight?`). Conditional display (`[:div {:data-show "$overweight?"}]`)
  and computed text bind to signals; the server pushes `patch-signals!` when
  reactions change. Procyon's `[:procyon.util/value {:rxn …}]` becomes either a
  signal binding or a targeted element patch.
- **Patch granularity.** From the editscript diff (`on-session-update`) we know
  exactly which field `:id`s and reaction ids changed → emit minimal
  `patch-elements!` (for fields whose rendered HTML changed, keyed by element id)
  + `patch-signals!` (for reactions). This is the dumb-terminal contract.
- **HTML shell** via Selmer (mycelium-web-template convention): one page that
  loads Datastar, opens the SSE stream (`data-on:load="@get('/form/:id/sse')"`),
  and contains the server-rendered initial view.

### Datastar transport notes
- Coordinate: `dev.data-star.clojure/sdk` + `dev.data-star.clojure/ring`. API:
  `d*/patch-elements!`, `d*/patch-signals!`, `d*/close-sse!`; `->sse-response`
  with `on-open`/`on-close` to register/unregister generators in the hub (the
  broadcast-set pattern from the SDK docs maps 1:1 to our `:datastar/hub`).
- **Server: ring-jetty9 (decided).** We replace **kit-undertow** with
  **ring-jetty9** — async + `StreamableResponseBody`, the cleanest path for the
  generic Datastar ring adapter and closest to vanilla Ring. Swap the
  `:server/http` Integrant component and the `kit.edge.server.*` require in
  `core.clj` accordingly (Phase 0).

---

## 6. Data Relationships — the core feature

The whole point is complex forms with rich data dependencies. How each kind is
modeled:

1. **Derived / computed fields (BMI ← height, weight).**
   Domino `:event` with `:inputs [:kg :m] :outputs [:bmi]`. Cascades are
   DAG-ordered automatically; chained derivations (A→B→C) just work. No view or
   transport code. ✔ already supported by Domino + procyon-editor.

2. **Conditional visibility / display logic.**
   Domino `:reaction` → Datastar signal → `data-show` / `data-attr`. (e.g.
   `:overweight?` toggles a warning block.)

3. **External service lookups (pull from a service).**
   Two paths:
   - **Import on identity** (`:imports`): when a key field (e.g. `:patient-id`)
     is set, hydrate fields from an external resource. Reuse Procyon's
     `document/inject-into-document` + a Mycelium resource client. Domino
     **incoming effect** triggers it.
   - **Async derived field**: Domino handlers may return a derefable
     (`future`/`promise`); the engine resolves before applying. Good for
     "look up X when Y changes." Long fetches run in the workflow cell and patch
     in when ready.

4. **DB-sourced option lists (dropdown values from the database).**
   Extend the field definition with a declarative source:
   ```clojure
   [:procedure {:id :procedure-id :type :enum
                :options {:source :db/query :query :procedures/active}}]
   ```
   A Mycelium cell (with the `:db` resource) resolves `:options` at render time
   (and on refresh) into concrete `:values`, cached per session. Static
   `:values` keep working unchanged.

5. **Validation.**
   Field `:type`/`:required?` from the model → render-time + transact-time
   checks. Cross-field validation = Domino events that compute an `:errors`
   reaction → patched as signals/`data-attr` (`aria-invalid`, help text).

6. **Effects / outputs (publish, export).**
   Domino outgoing effects and Procyon `:exports` (templated FHIR). Triggered via
   `POST /form/:id/action/:aid`; the effect handler can call Mycelium
   resources/clients.

---

## 7. Reuse Map — Procyon

| Procyon asset | Verdict | Notes |
|---------------|---------|-------|
| `procyon-editor/*` (editor, data, impl, locks, manager) | **Reuse directly** | Server-side Domino session layer; frontend-agnostic. Biggest win. |
| `apps/procyon` `form/compile.cljc` | **Reuse** | Form compilation, pre-initialize, snippet/subform expansion, `id->path`. |
| `apps/procyon` `session_manager.cljc` | **Reuse (adapt sink)** | `session->data`, `on-session-update` editscript diff. Replace Sente broadcast with Datastar hub. |
| `apps/procyon` `transactor.cljc` | **Reuse dispatch, swap transport** | Event routing logic kept; send-fn becomes Datastar. |
| `apps/procyon` `document.clj` | **Reuse** | `create-document!`, `inject-into-document` (imports), `publish-export` (exports), `:on-create` hooks. |
| Form document EDN shape (`demo/*.edn`) | **Adopt** | Proven `:data/:views/:imports/:exports` structure. |
| `procyon-components` widgets (Reagent/antd) | **Reuse design, rewrite impl** | Keep dispatch + markup-walk + widget-map shape + field defs; emit server HTML + Datastar instead of Reagent. |
| `procyon-builder` | **Ignore** | Stub/empty. |
| Sente WS client/server, re-frame, syn-antd, editscript-over-WS | **Drop** | Replaced by Datastar SSE. |

---

## 8. Decisions (resolved)

1. **HTTP server for SSE** — ✅ **ring-jetty9 (info.sunng), sync mode.** Replaced
   kit-undertow in Phase 0. **Phase 0 finding:** the Datastar *ring* adapter
   writes the response body synchronously (`StreamableResponseBody`) and the
   jetty9 async handler completes the response as soon as that write returns — so
   async mode closes the stream after the first event. The adapter keeps a stream
   open only by **blocking in `on-open`**, which works identically in sync mode
   and also lets all ordinary handlers stay plain `[req]->resp` fns. We therefore
   run **sync Jetty with a raised `:max-threads` (250)**. *Trade-off:* one server
   thread per open SSE connection. If connection counts get high, the http-kit
   Datastar adapter (true async, no thread-per-connection) is the swap; the SSE
   hub is abstracted so that change stays localized. Verified working: persistent
   multi-tick stream + multi-connection broadcast.
2. **Persistence** — ✅ **Atom + duratom** for documents, behind a
   `:store/documents` abstraction so a query DB (XTDB/datalevin) can slot in
   later. **Form definitions** live on disk as one EDN file per form, scanned
   from a `forms/` directory by `:store/forms` (the v1 "database" for forms;
   `get-form`/`save-form!`/`list-forms` API, swappable to a real DB later) — not
   enumerated in config.
3. **Multi-user editing/locking** — ✅ **Multi-user from the start.**
   `editor/locks.cljc` is *enforced* in the core reactive loop, and presence is
   broadcast over SSE. This makes the session/transport contract a per-connection
   concern from day one (see phasing changes below).
4. **Form authoring** — ✅ **EDN files first.** Forms are authored as EDN
   documents (like `demo/*.edn`); a visual builder is deferred to Phase 6.
5. **sci sandboxing** — Keep sci for evaluating form `:event`/`:reaction` fns
   (safe, matches Procyon). *(Open: confirm acceptable, else trusted-eval.)*

### Implications of "multi-user from start"
- The `:datastar/hub` is keyed by **document → {connection → sse-gen, user}**;
  broadcasts fan out to all peers on a document.
- `save-ids!` goes through `lock!`/`unlock!` (reuse `editor/locks.cljc`); a
  field-update is rejected/ignored if another user holds the lock, and lock
  state is itself broadcast (so peers see fields disable/grey out live).
- Editscript diffs in `on-session-update` now broadcast to *every* connection on
  the document, not just the originator — Procyon's design already supports this.
- Presence (who's connected, who holds which lock) is a derived, broadcastable
  slice of `session->data` (`:connections`, `:locks`).

---

## 9. Phased Implementation Plan

Each phase is independently runnable and demoable. TDD throughout.

### Phase 0 — Foundations & transport (de-risk SSE)
- **Swap the HTTP server to ring-jetty9** (replace `kit-undertow` in `deps.edn`,
  the `:server/http` component in `system.edn`, and the `kit.edge.server.*`
  require in `core.clj`).
- Add deps: `domino/core`, `dev.data-star.clojure/sdk` + `…/ring`, `selmer`, sci,
  editscript, duratom. Pull `procyon-editor` + needed `apps/procyon` `.cljc` as
  source (or local-root the libs).
- **Spike SSE on ring-jetty9.** Minimal route: open SSE via `->sse-response`,
  push a `patch-elements!` every second; confirm streaming + reconnect.
- **Spike Mycelium web wiring.** One `:reitit.routes/pages` route using
  `mw/workflow-handler` rendering a Selmer page (mirror mycelium-web-template).
- ✅ Exit: a Datastar page receives a server-pushed DOM patch over SSE on Jetty.

### Phase 1 — Document engine (no UI logic, server-only)
- Lift `procyon-editor` (data/impl/editor) + `form/compile.cljc`. Get a Domino
  session from an EDN form (start from `demo/v6.edn`, trimmed).
- Tests: load form → `initialize-ctx`; `transact` `:kg`/`:m` → assert `:bmi`
  recomputes; assert reaction values (`:overweight?`) update.
- `:store/forms` + `:store/documents` (atom) + `:session/manager` Integrant
  components.
- ✅ Exit: REPL/tests prove the reactive document works end-to-end server-side.

### Phase 2 — Server-side renderer
- `render-widget` multimethod for core widgets: `form`, `section`, `input-field`
  (string/number/text), `dropdown-select` (static `:values`), `read-only value`.
- `render-view`: walk `:markup`, dispatch widgets, emit HTML with Datastar
  attributes (`data-bind`, `data-on:input`, `data-show`).
- `GET /form/:id` workflow → full page (Selmer shell + initial view + initial
  signals from `session->data`).
- ✅ Exit: a form renders in the browser with initial values; no reactivity yet.

### Phase 3 — Reactive loop, multi-user (the core demo: BMI)
- SSE endpoint + `:datastar/hub` keyed by **document → {connection → sse-gen,
  user}** (register on `on-open`, unregister on `on-close`).
- `POST /form/:id/field/:fid` workflow: parse signal → `lock!` (reuse
  `editor/locks.cljc`) → `save-ids!` (Domino transact) → `unlock!` →
  `on-session-update` editscript diff → `diff->patches` (`patch-elements!` for
  changed fields + `patch-signals!` for reactions) → **broadcast to all
  connections on the document**.
- **Multi-user from the start:** lock state and presence (`:connections`,
  `:locks` from `session->data`) are broadcast too — peers see fields held by
  others disable/grey out live, and conflicting updates are rejected.
- ✅ Exit: two browsers on the same document — one edits height/weight, the other
  sees BMI update live, the warning toggle, and the edited field lock — all
  server-driven, browsers as dumb terminals.

### Phase 4 — Data relationships at scale
- DB-sourced options (`:options {:source …}`) resolved via a Mycelium `:db`
  resource (Decision #2 store).
- External imports: `:imports` hydration on key-field set (reuse
  `inject-into-document`) with a stub service client; async derived-field path.
- Cross-field validation → `:errors` reactions → invalid-state rendering.
- Collections/nested subcontexts (Domino subcontexts; `rx-in-ctx`/`subctx-tree`).
- ✅ Exit: a non-trivial form (lookups + dynamic dropdowns + validation +
  repeated groups) works.

### Phase 5 — Documents lifecycle & exports
- Create/list/load/save documents (`document.clj` reuse); duratom persistence.
- `:exports` (templated output) via `POST /form/:id/action/:aid`.
- Multiple views per document; read-only summary view.
- ✅ Exit: full CRUD on documents + export action.

### Phase 6 — Hardening / optional
- Visual form builder (Datastar) — author `:data`/`:views` in-app (deferred from
  v1, which authors forms as EDN files).
- Form revisions/migrations (per Procyon NOTES).
- Resilience (SSE reconnect, session recovery), auth, query/search backend
  (XTDB/datalevin behind the existing `:store/documents` abstraction).

---

## 10. Net-New Code Inventory (what we actually write)

1. **Datastar transport**: `:datastar/hub` component + `diff->patches`
   (editscript diff → `patch-elements!`/`patch-signals!`).
2. **Server renderer**: `render-widget` multimethod + `render-view` + Selmer
   shell.
3. **Mycelium cells/workflows**: per-route pipelines (`parse` / `apply-change` /
   `broadcast` / `render`) + resources wiring.
4. **Option/source resolver**: declarative DB/service-backed dropdowns.
5. **Integrant glue**: `:store/forms`, `:store/documents`, `:session/manager`,
   `:datastar/hub`, `:clients/*` in `system.edn`.

Everything else — the reactive document engine, compilation, session
management, diffing, imports/exports — is **lifted from Procyon**.
