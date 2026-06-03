(ns yogthos.stepvine.signals
  "The Datastar signal vocabulary: the mapping from field/reaction ids to the
   signal names the browser binds to, plus the projection of a live editor
   session into the flat `{signal-name -> value}` maps that seed the form and
   diff/broadcast over SSE.

   This is the *wire contract* between the renderer (which emits `data-bind`/`$`
   references) and the signal reader (which parses Datastar's posted signals back
   to field ids): both sides MUST agree on `signal-name`'s sanitization, so it
   lives here as the single source of truth. `render.clj` and the widget
   namespaces require this module for the names; `web.sse`/`session` require it
   for the projection.

   Collections (Domino subcontexts) expand to per-item signals
   `<coll>_<idx>_<field>`, recursing to any depth, so per-item derived fields
   recompute and broadcast independently.

   See `endpoints.clj` for the companion URL scheme and ARCHITECTURE.md for the
   request flow."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.editor.data :as data]
   [yogthos.stepvine.editor.impl :as impl]))

;; --- Names & signals ------------------------------------------------------

(defn signal-name
  "Datastar signal name for a field/reaction id. Datastar signals must be valid
   identifiers, so non-alphanumeric chars (e.g. the `?` in :overweight?, the `-`
   in :bmi-category) are collapsed to `_`."
  [id]
  (-> (if (or (keyword? id) (symbol? id)) (name id) (str id))
      (str/replace #"[^a-zA-Z0-9]+" "_")
      (str/replace #"^_+|_+$" "")))

(defn $ [id] (str "$" (signal-name id)))

(defn item-path
  "The collection path prefix for the current item as `[coll idx coll idx …]`, or
   nil at top level. Supports the generalized `:path` and the legacy `:coll`/`:idx`
   item shape (a single level)."
  [ctx]
  (when-let [{:keys [path coll idx]} (:item ctx)]
    (or (seq path) (when coll [coll idx]))))

(defn item-signal-name
  "Signal name for a field, item-aware: inside a collection item it is
   <coll>_<idx>[_<coll>_<idx>…]_<field>, otherwise just <field>. Supports nesting
   to any depth via the item path."
  [ctx id]
  (if-let [path (item-path ctx)]
    (str (str/join "_" (map signal-name path)) "_" (signal-name id))
    (signal-name id)))

(defn item-$ [ctx id] (str "$" (item-signal-name ctx id)))

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

(defn nested-collection-field-opts
  "Build {fid -> fopts} from a collection field's :schema model (for recursion)."
  [fopts]
  (into {} (map (fn [entry]
                  (let [o (if (map? (second entry)) (second entry) {})]
                    [(:id o) o])))
        (get-in fopts [:schema :model])))

(defn collection-signal-map
  "Flat {signal-name -> value} for every field of every item across collections,
   recursing into nested collections — a nested item field seeds the full-path
   signal <coll>_<idx>_<coll2>_<idx2>_<field>."
  [collections]
  (letfn [(items->sigs [prefix field-opts items order]
            (reduce
             (fn [acc idx]
               (let [ipfx (str prefix (signal-name idx) "_")]
                 (reduce-kv
                  (fn [acc fid v]
                    (if (:collection? (get field-opts fid))
                      ;; nested collection: v is {idx2 {…}} — recurse
                      (merge acc (items->sigs (str ipfx (signal-name fid) "_")
                                              (nested-collection-field-opts (get field-opts fid))
                                              v (keys v)))
                      (assoc acc (str ipfx (signal-name fid)) v)))
                  acc (get items idx))))
             {} order))]
    (reduce-kv
     (fn [acc coll-id {:keys [order items field-opts]}]
       (merge acc (items->sigs (str (signal-name coll-id) "_") field-opts items order)))
     {} collections)))

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

(defn scalar-field-ids
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
