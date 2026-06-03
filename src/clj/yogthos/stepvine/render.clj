(ns yogthos.stepvine.render
  "Server-side view renderer.

   Walks a form's `:views/:markup` (hiccup with widget keywords) and emits HTML
   carrying Datastar bindings. The renderer *design* — dispatch on a namespaced
   `:component` keyword, a recursive markup walk, field definitions pulled from
   the Domino model — drives server-rendered HTML + `data-*` attributes (rather
   than a client-side Reagent/antd implementation).

   The browser is a dumb terminal: inputs two-way `data-bind` to server-owned
   signals and `data-on:input` POST intent; computed fields and reaction values
   are signal-bound text.

   Collections (Domino subcontexts) render each item from the collection widget's
   body template with item-scoped signal names `<coll>_<idx>_<field>`, so per-item
   derived fields recompute and broadcast independently.

   Render context (built by `session->context`):
     :values      {field-id -> value}     top-level field values
     :rxns        {reaction-id -> value}   reaction values
     :field-opts  {field-id -> opts}       :type / :required? / :path from model
     :collections {coll-id -> {:order [idx] :field-opts {} :items {idx {fid val}}}}
     :item        {:coll :idx} when rendering inside a collection item
     :aliases     {\"c\" \"stepvine.components\"}  widget namespace expansion
     :doc-id      document id, for endpoint URLs

   The Datastar signal-name vocabulary + session->signal projection live in
   `signals.clj`; the field/collection endpoint URL scheme lives in
   `endpoints.clj`. Widgets require those directly — the renderer here owns only
   the markup walk, `render-widget` dispatch, and the collection-node projection."
  (:require
   [clojure.walk :as walk]
   [hiccup2.core :as h]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.signals :as signals]))

;; --- Widget keyword resolution --------------------------------------------

(defn resolve-component
  "Expand a widget keyword's namespace alias, e.g. with {\"c\" \"stepvine.components\"}
   :c/input-field -> :stepvine.components/input-field. Unaliased keywords pass
   through unchanged."
  [aliases kw]
  (if-let [full (get aliases (namespace kw))]
    (keyword full (name kw))
    kw))

(defn- html-tag?
  "A plain HTML element keyword (no namespace), e.g. :div, :p, :h1."
  [kw]
  (and (keyword? kw) (nil? (namespace kw))))

(defn- widget-node?
  [node]
  (and (vector? node) (keyword? (first node)) (namespace (first node))))

;; --- Widget dispatch ------------------------------------------------------

(declare render-node)

(defn render-children
  "Render a node's child markup (public so widget namespaces can recurse)."
  [ctx children]
  (map (partial render-node ctx) children))

(defmulti render-widget
  "Render a resolved widget node to hiccup.
   Dispatch on the alias-expanded component keyword."
  (fn [_ctx component _attrs _body] component))

(defmethod render-widget :default
  [_ctx component _attrs _body]
  [:div.unknown-widget (str "Unknown widget: " component)])

;; --- Markup walk ----------------------------------------------------------

(defn perm-ok?
  "Granular field permission (§parity): a field's `:read-roles`/`:write-roles`
   permit the ctx user when they're empty, the user is admin, or the user holds one
   of the roles. Only consulted when the ctx carries permission info (`:perm-roles`)
   — re-render contexts without it render normally and rely on server enforcement."
  [ctx roles]
  (or (empty? roles)
      (:perm-admin? ctx)
      (boolean (some (set roles) (:perm-roles ctx)))))

(defn render-node
  "Render one markup node to hiccup. Strings pass through (hiccup escapes them);
   plain HTML vectors render as elements with children recursed; namespaced
   vectors dispatch to render-widget. A field-bound widget the user may not READ
   is omitted; one they may not WRITE is forced read-only."
  [ctx node]
  (cond
    (string? node) node
    (number? node) node
    (nil? node)    nil

    (widget-node? node)
    (let [[kw a & more] node
          has-attrs? (map? a)
          attrs      (if has-attrs? a {})
          body       (if has-attrs? more (when a (cons a more)))
          component  (resolve-component (:aliases ctx) kw)
          fopts      (when (contains? ctx :perm-roles)
                       (get-in ctx [:field-opts (:id attrs)]))
          ;; editable only when the user's roles permit AND (if :writable-in is set)
          ;; the document is in one of those workflow states
          role-no?   (and fopts (:write-roles fopts) (not (perm-ok? ctx (:write-roles fopts))))
          state-no?  (and fopts (:writable-in fopts) (:workflow-state ctx)
                          (not (contains? (set (:writable-in fopts)) (:workflow-state ctx))))]
      (if (and fopts (:read-roles fopts) (not (perm-ok? ctx (:read-roles fopts))))
        nil                                                  ; field hidden from this user
        (render-widget ctx component
                       (cond-> attrs (or role-no? state-no?) (assoc :read-only true))
                       body)))

    (and (vector? node) (html-tag? (first node)))
    (let [[tag a & more] node
          has-attrs? (map? a)
          attrs      (if has-attrs? a {})
          body       (if has-attrs? more (when a (cons a more)))]
      (into [tag attrs] (render-children ctx body)))

    :else node))

;; Concrete components live under `yogthos.stepvine.components.*` (registered via
;; the `yogthos.stepvine.components` namespace) — render.clj is just the engine.
;; Signal names + signal-map projection: see `signals.clj`.

;; --- Public entry points --------------------------------------------------

(defn session->context
  "Build a render context from a live editor session and a view id."
  [session view-id doc-id]
  (let [form       (:form session)
        field-opts (:field-opts session)
        view       (get-in form [:views view-id])]
    {:values      (into {} (map (fn [id] [id (impl/value session id)]))
                        (signals/scalar-field-ids field-opts))
     :rxns        (into {} (map (fn [id] [id (impl/value session id)]))
                        (map :id (get-in form [:data :reactions])))
     :field-opts  field-opts
     :collections (signals/collections-data session)
     :view-state  (:view-state session)   ; table sort/page/filter (presentation)
     :aliases     (get-in view [:opts :widget-namespaces])
     :doc-id      doc-id}))

(defn render-view
  "Render a view's markup to an HTML string for the given context."
  [ctx markup]
  (str (h/html (render-node ctx markup))))

(defn view-markup
  [session view-id]
  (get-in session [:form :views view-id :markup]))

(defn theme-href
  "Resolve a view's optional `:opts {:theme …}` to a stylesheet href, or nil.
   A full URL (http…) or absolute path (/…) is used verbatim; a bare name like
   \"dark\" maps to \"/css/dark.css\"."
  [session view-id]
  (when-let [theme (get-in session [:form :views view-id :opts :theme])]
    (let [t (if (keyword? theme) (name theme) (str theme))]
      (cond
        (re-find #"^https?://" t) t
        (.startsWith t "/")       t
        :else                     (str "/css/" t ".css")))))

(def ^:private collection-widgets
  "Widgets that render a collection container and can be re-rendered standalone."
  #{:stepvine.components/collection :stepvine.components/table})

(defn find-collection-node
  "Find the collection/table markup node with `:id` coll-id in a view, for
   re-rendering the container on add/remove/sort/page/move. coll-id is a keyword."
  [markup aliases coll-id]
  (let [found (atom nil)]
    (walk/prewalk
     (fn [n]
       (when (and (nil? @found)
                  (widget-node? n)
                  (collection-widgets (resolve-component aliases (first n)))
                  (= coll-id (:id (second n))))
         (reset! found n))
       n)
     markup)
    @found))

(defn render-collection
  "Render a single collection's container HTML (for element patches on
   add/remove). Returns nil if the collection isn't in the view."
  [ctx session view-id coll-id]
  (when-let [node (find-collection-node (view-markup session view-id)
                                        (:aliases ctx) coll-id)]
    (str (h/html (render-node ctx node)))))

(def ^:private dropdown-widgets
  #{:stepvine.components/dropdown :stepvine.components/dropdown-select})

(defn dependent-dropdown-nodes
  "Top-level dropdown markup nodes whose `:depends-on` is `parent-id`, each with
   its field id — for re-rendering a dependent select when its parent changes.
   `parent-id` is a keyword."
  [markup aliases parent-id]
  (let [found (atom [])]
    (walk/prewalk
     (fn [n]
       (when (and (widget-node? n)
                  (dropdown-widgets (resolve-component aliases (first n)))
                  (= parent-id (some-> (:depends-on (second n)) keyword)))
         (swap! found conj {:id (:id (second n)) :node n}))
       n)
     markup)
    @found))

(defn cascade-closure
  "The dropdowns transitively dependent on `parent-id` over the `:depends-on`
   graph — `[{:id :node} …]` in breadth-first order (direct children first, then
   grandchildren, …). The basis for a full-depth cascade: a parent change ripples
   to every descendant dropdown. Cycle-safe."
  [markup aliases parent-id]
  (loop [queue [parent-id], seen #{parent-id}, out []]
    (if-let [fid (first queue)]
      (let [deps  (dependent-dropdown-nodes markup aliases fid)
            fresh (remove (comp seen keyword :id) deps)]
        (recur (into (subvec (vec queue) 1) (map (comp keyword :id) fresh))
               (into seen (map (comp keyword :id) fresh))
               (into out fresh)))
      out)))

(defn dropdowns-depending-on
  "Dropdown nodes whose `:depends-on` is in `changed` (a set of field ids) — the
   dropdowns whose options must be re-rendered because their parent's value moved
   in this transaction. Driven by the engine's change-set, so only what actually
   changed re-renders (and a child whose own value didn't move isn't re-rendered
   unless its parent's did)."
  [markup aliases changed]
  (let [changed (set changed)]
    (distinct
     (mapcat #(dependent-dropdown-nodes markup aliases %) changed))))
