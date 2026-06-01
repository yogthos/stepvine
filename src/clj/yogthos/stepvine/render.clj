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
     :doc-id      document id, for endpoint URLs"
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [hiccup2.core :as h]
   [yogthos.stepvine.editor.data :as data]
   [yogthos.stepvine.editor.impl :as impl]))

;; --- Names & signals ------------------------------------------------------

(defn signal-name
  "Datastar signal name for a field/reaction id. Datastar signals must be valid
   identifiers, so non-alphanumeric chars (e.g. the `?` in :overweight?, the `-`
   in :bmi-category) are collapsed to `_`."
  [id]
  (-> (name id)
      (str/replace #"[^a-zA-Z0-9]+" "_")
      (str/replace #"^_+|_+$" "")))

(defn $ [id] (str "$" (signal-name id)))

(defn item-signal-name
  "Signal name for a field, item-aware: inside a collection item it is
   <coll>_<idx>_<field>, otherwise just <field>."
  [ctx id]
  (if-let [{:keys [coll idx]} (:item ctx)]
    (str (signal-name coll) "_" idx "_" (signal-name id))
    (signal-name id)))

(defn item-$ [ctx id] (str "$" (item-signal-name ctx id)))

(defn field-post-url
  "POST endpoint for a field change, item-aware."
  [ctx id]
  (if-let [{:keys [coll idx]} (:item ctx)]
    (str "/doc/" (:doc-id ctx) "/coll/" (name coll) "/" idx "/field/" (name id))
    (str "/doc/" (:doc-id ctx) "/field/" (name id))))

(defn field-lock-url   [ctx id] (str (field-post-url ctx id) "/lock"))
(defn field-unlock-url [ctx id] (str (field-post-url ctx id) "/unlock"))

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

(defn render-node
  "Render one markup node to hiccup. Strings pass through (hiccup escapes them);
   plain HTML vectors render as elements with children recursed; namespaced
   vectors dispatch to render-widget."
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
          component  (resolve-component (:aliases ctx) kw)]
      (render-widget ctx component attrs body))

    (and (vector? node) (html-tag? (first node)))
    (let [[tag a & more] node
          has-attrs? (map? a)
          attrs      (if has-attrs? a {})
          body       (if has-attrs? more (when a (cons a more)))]
      (into [tag attrs] (render-children ctx body)))

    :else node))

;; --- Collections: data + signals ------------------------------------------

(defn- apply-order
  "Reorder a collection's :order by an explicit view-state order vector: stored
   order first (present items only), then any items not in it (newly added)."
  [{:keys [order] :as cd} explicit]
  (if (seq explicit)
    (let [present (set order)
          ordered (vec (filter present explicit))
          extra   (vec (remove (set ordered) order))]
      (assoc cd :order (into ordered extra)))
    cd))

(defn collections-data
  "Per-collection render data from a live session:
   {coll-id {:order [idx...] :field-opts {fid -> opts} :items {idx {id -> value}}}}.
   Each item's value map carries both model fields AND the per-item reaction
   values declared on the collection's :schema. (Delegates to the editor seam,
   which reconstructs collections on top of the underlying engine, then applies
   any per-collection row ordering from the session's table view-state.)"
  [session]
  (let [view (:view-state session)]
    (reduce-kv (fn [acc coll cd]
                 (assoc acc coll (apply-order cd (get-in view [coll :order]))))
               {}
               (data/collections-data session))))

(defn- coll-sig [coll-id idx fid]
  (str (signal-name coll-id) "_" idx "_" (signal-name fid)))

(defn collection-signal-map
  "Flat {signal-name -> value} for every field of every item across collections."
  [collections]
  (reduce-kv
   (fn [acc coll-id {:keys [order items]}]
     (reduce (fn [acc idx]
               (reduce-kv (fn [acc fid v] (assoc acc (coll-sig coll-id idx fid) v))
                          acc (get items idx)))
             acc order))
   {}
   collections))

;; --- Signal maps ----------------------------------------------------------

(defn signal-map
  "Clojure map of all signals -> values for a render context (top-level fields +
   reactions + collection item fields)."
  [ctx]
  (merge
   (reduce-kv (fn [acc id v] (assoc acc (signal-name id) v))
              {}
              (merge (:values ctx) (:rxns ctx)))
   (collection-signal-map (:collections ctx))))

(defn- scalar-field-ids
  "Top-level field ids excluding collection containers (those expand to
   item-scoped signals instead)."
  [field-opts]
  (remove (fn [id] (:collection? (get field-opts id))) (keys field-opts)))

(defn all-field-opts
  "Field options for a session, flattened across nesting: top-level fields merged
   with every collection's item fields. Field ids are unique within a form, so
   the flat merge is unambiguous — used to resolve dropdown option sources for
   collection-item fields (e.g. the builder's :ftype) as well as top-level ones."
  [session]
  (apply merge
         (:field-opts session)
         (map :field-opts (vals (collections-data session)))))

(defn session->signal-map
  "All signals -> current values, read straight from a live editor session.
   Used to diff and broadcast over SSE.

   Nil field/reaction values are emitted as \"\" (not null): a Datastar
   patch-signals applies JSON-merge-patch semantics, where a null value *deletes*
   the signal. Deleting a field signal mid-edit (e.g. the late-arriving on-connect
   sync) destroys its data-bind, so an unset field must sync as \"\", matching the
   form's initial data-signals seed."
  [session]
  (let [field-ids (scalar-field-ids (:field-opts session))
        rxn-ids   (map :id (get-in session [:form :data :reactions]))]
    (into {}
          (map (fn [[k v]] [k (if (nil? v) "" v)]))
          (merge
           (into {}
                 (map (fn [id] [(signal-name id) (impl/value session id)]))
                 (concat field-ids rxn-ids))
           (collection-signal-map (collections-data session))))))

;; Concrete widgets live in `yogthos.stepvine.widgets.*` (registered via the
;; `yogthos.stepvine.widgets` namespace) — render.clj is just the engine.

;; --- Public entry points --------------------------------------------------

(defn session->context
  "Build a render context from a live editor session and a view id."
  [session view-id doc-id]
  (let [form       (:form session)
        field-opts (:field-opts session)
        view       (get-in form [:views view-id])]
    {:values      (into {} (map (fn [id] [id (impl/value session id)]))
                        (scalar-field-ids field-opts))
     :rxns        (into {} (map (fn [id] [id (impl/value session id)]))
                        (map :id (get-in form [:data :reactions])))
     :field-opts  field-opts
     :collections (collections-data session)
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
