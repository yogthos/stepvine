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
   [jsonista.core :as json]
   [yogthos.stepvine.editor.data :as data]
   [yogthos.stepvine.editor.impl :as impl]
   [domino.core :as d]))

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

(defn- render-children [ctx children]
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

(defn collections-data
  "Per-collection render data from a live session:
   {coll-id {:order [idx...] :field-opts {fid -> opts} :items {idx {id -> value}}}}.
   Each item's value map carries both model fields AND the per-item reaction
   values declared on the collection's :schema."
  [session]
  (let [ctx (::data/ctx session)]
    (reduce-kv
     (fn [acc coll-id sub]
       (if (::d/collection? sub)
         (let [elements (::d/elements sub)
               order    (vec (keys elements))
               fopts    (or (some-> elements first val ::d/id->opts) {})
               rxn-ids  (map :id (get-in (::d/id->opts ctx) [coll-id :schema :reactions]))
               items    (into {}
                              (map (fn [idx]
                                     (let [ids (concat (keys (::d/id->opts (get elements idx))) rxn-ids)]
                                       [idx (into {}
                                                  (map (fn [id] [id (impl/value session [coll-id idx id])]))
                                                  ids)])))
                              order)]
           (assoc acc coll-id {:order order :field-opts fopts :items items}))
         acc))
     {}
     (::d/subcontexts ctx))))

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

;; --- Widgets --------------------------------------------------------------

(defmethod render-widget :stepvine.components/form
  [ctx _component attrs body]
  ;; The form seeds all signals once via data-signals (field/reaction/collection
  ;; values plus system signals: this client's uid, presence count and per-field
  ;; lock map), opens the SSE stream on load — tagged with uid so the server can
  ;; release this client's locks on disconnect — and suppresses native submit.
  (let [uid  (:uid ctx)
        ;; Datastar drops null-valued signals from data-signals, so a field
        ;; seeded null would never become a bindable/sendable signal. Seed nils
        ;; as "" so every field signal exists from the start.
        seed (into {} (map (fn [[k v]] [k (if (nil? v) "" v)]))
                   (merge (signal-map ctx) {"uid" uid "presence" 1 "locks" {}}))]
    ;; Rendered as a <div>, not a <form>: there is no submit (inputs POST via
    ;; data-on:input), and an empty data-on:submit value crashes Datastar's
    ;; engine (ValueRequired), which would break every binding on the page.
    ;; data-signals seeds state; data-init opens the SSE stream on load.
    [:div (merge {"data-signals" (json/write-value-as-string seed)
                  "data-init"    (str "@get('/doc/" (:doc-id ctx) "/sse')")}
                 (dissoc attrs :id))
     (render-children ctx body)]))

(defmethod render-widget :stepvine.components/input-field
  [ctx _component {:keys [id label read-only error]} _body]
  (let [opts     (get-in ctx [:field-opts id])
        nm       (name id)
        in-item? (boolean (:item ctx))
        sig      (item-signal-name ctx id)   ; <field> or <coll>_<idx>_<field>
        value    (get-in ctx [:values id])
        number?  (= :number (:type opts))
        err-sig  (when error ($ error))]
    [:div.field
     [:label label]
     [:input
      ;; two-way bind via the value form `data-bind="<signal>"` (bare signal
      ;; name; references elsewhere use $name). Datastar takes control of the
      ;; input once its async module loads + the data-init SSE opens, re-applying
      ;; the data-signals seed — so the seed must already carry this field.
      (cond-> {:type  (if number? "number" "text")
               :value (if (nil? value) "" (str value))}
        true              (assoc "data-bind" sig)
        ;; only top-level fields get a stable id/name (item ids would collide)
        (not in-item?)    (assoc :id nm :name nm)
        (:required? opts) (assoc :required true)
        read-only         (assoc :readonly true)
        err-sig           (assoc "data-attr:aria-invalid" (str "!!" err-sig))
        (not read-only)
        ;; edit + server-authoritative locking, uniform for top-level and items
        ;; (item urls/signals are coll-scoped via field-*-url and item-signal-name)
        (assoc "data-on:input__debounce.300ms" (str "@post('" (field-post-url ctx id) "')")
               "data-on:focus" (str "@post('" (field-lock-url ctx id) "')")
               "data-on:blur"  (str "@post('" (field-unlock-url ctx id) "')")
               ;; clean boolean: when unlocked $locks.<sig> is undefined, and a
               ;; bare `&&` would yield undefined (which datastar treats as set);
               ;; `!!` forces false so the disabled attribute is removed.
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]
     (when err-sig
       [:span.error {"data-text" err-sig "data-show" err-sig}])]))

(defmethod render-widget :stepvine.components/dropdown-select
  [ctx _component {:keys [id label]} _body]
  (let [nm       (name id)
        in-item? (boolean (:item ctx))
        sig      (item-signal-name ctx id)   ; <field> or <coll>_<idx>_<field>
        current  (get-in ctx [:values id])
        options  (get-in ctx [:options id])]
    [:div.field
     [:label label]
     (into [:select
            (cond-> {"data-bind" sig   ; value form: bare (item-scoped) signal name
                     "data-on:change" (str "@post('" (field-post-url ctx id) "')")
                     "data-on:focus"  (str "@post('" (field-lock-url ctx id) "')")
                     "data-on:blur"   (str "@post('" (field-unlock-url ctx id) "')")
                     "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")}
              ;; only top-level fields get a stable id/name (item ids would collide)
              (not in-item?) (assoc :id nm :name nm))
            [:option {:value ""} "— select —"]]
           (for [o options]
             [:option (cond-> {:value (str (:value o))}
                        (= (:value o) current) (assoc :selected true))
              (:label o)]))]))

(defmethod render-widget :stepvine.util/value
  [ctx _component {:keys [rxn id default]} _body]
  ;; Bind text to a signal. Inside a collection item both :rxn and :id are
  ;; item-scoped and read from the item's value map (which carries per-item
  ;; reaction values); at top level :rxn reads reactions and :id reads fields.
  (let [sig-id   (or rxn id)
        in-item? (boolean (:item ctx))
        sig      (if in-item? (item-$ ctx sig-id) (if rxn ($ rxn) ($ id)))
        current  (if in-item?
                   (get (:values ctx) sig-id)
                   (get (if rxn (:rxns ctx) (:values ctx)) sig-id))]
    [:span {"data-text" sig}
     (str (if (nil? current) (or default "") current))]))

(defmethod render-widget :stepvine.components/show
  [ctx _component {:keys [when]} body]
  [:div {"data-show" ($ when)}
   (render-children ctx body)])

(defmethod render-widget :stepvine.components/section
  [ctx _component {:keys [title] :as attrs} body]
  [:fieldset (dissoc attrs :title :id)
   (when title [:legend title])
   (render-children ctx body)])

(defmethod render-widget :stepvine.components/action
  [ctx _component {:keys [action label]} _body]
  ;; Triggers a server-side action (e.g. a templated export) for this document.
  [:button.action
   {"data-on:click" (str "@post('/doc/" (:doc-id ctx) "/action/" (name action) "')")}
   (or label (str "Run " (name action)))])

(defmethod render-widget :stepvine.components/build-button
  [ctx _component {:keys [label]} _body]
  ;; Form-builder: generate + save a real form from this builder document.
  [:button.build
   {"data-on:click" (str "@post('/doc/" (:doc-id ctx) "/build')")}
   (or label "Build form")])

(defmethod render-widget :stepvine.components/collection
  [ctx _component {:keys [id]} body]
  (let [coll-id id
        {:keys [order field-opts items]} (get-in ctx [:collections coll-id])
        doc-id   (:doc-id ctx)
        coll-nm  (name coll-id)]
    [:div.collection {:id (str "coll-" coll-nm)}
     (for [idx order]
       (let [item-ctx (assoc ctx
                             :item {:coll coll-id :idx idx}
                             :values (get items idx)
                             :field-opts field-opts)]
         [:div.coll-item {:id (str "item-" coll-nm "-" idx)}
          (render-children item-ctx body)
          [:button.remove
           {"data-on:click"
            (str "@post('/doc/" doc-id "/coll/" coll-nm "/" idx "/remove')")}
           "Remove"]]))
     [:button.add
      {"data-on:click" (str "@post('/doc/" doc-id "/coll/" coll-nm "/add')")}
      "+ Add"]]))

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
     :aliases     (get-in view [:opts :widget-namespaces])
     :doc-id      doc-id}))

(defn render-view
  "Render a view's markup to an HTML string for the given context."
  [ctx markup]
  (str (h/html (render-node ctx markup))))

(defn view-markup
  [session view-id]
  (get-in session [:form :views view-id :markup]))

(defn find-collection-node
  "Find the [:c/collection {:id coll-id} ...] markup node in a view, for
   re-rendering the container on add/remove. coll-id is a keyword."
  [markup aliases coll-id]
  (let [found (atom nil)]
    (walk/prewalk
     (fn [n]
       (when (and (nil? @found)
                  (widget-node? n)
                  (= :stepvine.components/collection (resolve-component aliases (first n)))
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
