(ns yogthos.stepvine.editor.data
  (:require [domino.core :as d]
            [clojure.set :refer [union]]))

;; ==============================================================================
;; Indirection around domino so the engine API can change without touching the
;; rest of the editor. Targets the current domino (0.4.0): a model + events +
;; effects DAG. Three things the editor relied on were dropped from domino and
;; are reconstructed here:
;;
;;   * Event handler arity. domino calls `(handler ctx inputs outputs)`; stepvine
;;     form handlers take a single `{:inputs .. :outputs .. :ctx ..}` map and
;;     return an outputs map. `bridge-event` adapts between them.
;;
;;   * Reactions (derived *display* values). domino events are LAZY (a field
;;     stays nil until an input is transacted); reactions are EAGER (a blank
;;     document shows `:bmi-category` "n/a" before any edit). So reactions are not
;;     modelled as events — the editor computes them itself in dependency order
;;     after init and after every transact and stashes them on the ctx under
;;     `::reactions`. They read back through `get-value` like fields.
;;
;;   * Collections (per-item subcontexts). A collection field holds a map of
;;     items, each with its own schema (model/events/reactions). domino has no
;;     subcontexts, so each item is projected onto plain nested paths
;;     `[coll idx field]` with vector ids; a *live schema* (base + one set of
;;     model/events per current item) is rebuilt from the db on every transact,
;;     and re-initialised. Re-init from the db preserves event laziness for free:
;;     `initial-transaction` only fires events for db values that are present, so
;;     an untouched item's derived fields stay nil.

;; ---- small utils -------------------------------------------------------------

(defn- dissoc-in [m path]
  (let [path (vec path)]
    (if (= 1 (count path))
      (dissoc m (first path))
      (update-in m (pop path) dissoc (peek path)))))

(defn- bridge-event
  "domino calls `(handler ctx inputs outputs)`; stepvine handlers take one map."
  [event]
  (update event :handler
          (fn [h]
            (fn [ctx inputs outputs]
              (h {:inputs inputs :outputs outputs :ctx ctx})))))

;; ---- collections -------------------------------------------------------------
;; A top-level model entry tagged `:collection? true` carries an item `:schema`
;; (its own model/events/reactions). Items live at db path `[coll idx]`.

(defn collection-fields
  "[{:id :members :seg :members :schema {…}} …] for every collection in a model."
  [model]
  (keep (fn [entry]
          (let [seg  (first entry)
                opts (when (map? (second entry)) (second entry))]
            (when (:collection? opts)
              {:id (:id opts) :seg seg :schema (:schema opts)})))
        model))

(defn- item-indices [db {:keys [seg]}] (keys (get db seg)))

(defn- item-id
  "Synthetic FLAT keyword id for an item field. domino's schema validation
   flattens event input/output ids, so compound (vector) ids are rejected; the
   model keeps the nested path while the id is a collision-free keyword."
  [seg idx field]
  (keyword (str (name seg) "__" idx "__" (name field))))

(defn- item-model
  "Nested model entry projecting an item's fields under `[seg idx …]`. Branch
   segments carry no opts map — domino validates the raw model by flattening it,
   and a map without `:id` is treated as invalid. Item fields are tagged
   `:item? true` so they are excluded from the top-level editable field set."
  [seg idx schema]
  [seg (into [idx]
             (map (fn [entry]
                    (let [fseg  (first entry)
                          fopts (if (map? (second entry)) (second entry) {})]
                      [fseg (assoc fopts :id (item-id seg idx (:id fopts)) :item? true)]))
                  (:model schema)))])

(defn- item-events
  "An item's events, rewritten to the synthetic item ids and bridged so the form
   handler still sees/returns local field ids."
  [seg idx schema]
  (map (fn [{:keys [inputs outputs] h :handler}]
         (let [kid (fn [f] (item-id seg idx f))]
           {:inputs  (mapv kid inputs)
            :outputs (mapv kid outputs)
            :handler (fn [_ctx in _out]
                       (let [local (into {} (map (fn [f] [f (get in (kid f))])) inputs)]
                         (into {} (map (fn [[k v]] [(kid k) v])) (h {:inputs local}))))}))
       (:events schema)))

(defn- item-reaction-defs [seg idx schema]
  (map (fn [{:keys [id args] f :fn}]
         {:id   [seg idx id]
          :args (mapv (fn [a] [seg idx a]) args)
          :fn   f})
       (:reactions schema)))

;; ---- live schema (base + per-item) -------------------------------------------

(defn- live-schema
  "domino schema (model + events) for the current item set in `db`."
  [form db]
  (let [{:keys [model events]} (:data form)
        colls (collection-fields model)]
    {:model  (into (vec model)
                   (for [{:keys [seg schema] :as c} colls
                         idx (item-indices db c)]
                     (item-model seg idx schema)))
     :events (into (mapv bridge-event events)
                   (for [{:keys [seg schema] :as c} colls
                         idx (item-indices db c)
                         ev  (item-events seg idx schema)]
                     ev))}))

;; ---- reactions (editor-owned, eager) -----------------------------------------

(declare id->path)

(defn- topo-reactions
  "Order reactions so each comes after the reactions it depends on."
  [reaction-defs]
  (let [rxn-ids (set (map :id reaction-defs))]
    (loop [order [] placed #{} remaining (vec reaction-defs) guard (inc (count reaction-defs))]
      (cond
        (empty? remaining) order
        (zero? guard)      (into order remaining)
        :else
        (let [{ready true later false}
              (group-by (fn [{:keys [args]}]
                          (every? #(or (not (rxn-ids %)) (placed %)) args))
                        remaining)]
          (if (empty? ready)
            (into order remaining)
            (recur (into order ready) (into placed (map :id ready)) (vec later) (dec guard))))))))

(defn- all-reaction-defs [form db]
  (let [{:keys [model reactions]} (:data form)
        colls (collection-fields model)]
    (topo-reactions
     (into (vec reactions)
           (for [{:keys [seg schema] :as c} colls
                 idx (item-indices db c)
                 r   (item-reaction-defs seg idx schema)]
             r)))))

(defn- compute-reactions
  "`{reaction-id -> value}` from the current db, in dependency order."
  [ctx ordered-defs]
  (let [db (::d/db ctx)]
    (reduce
     (fn [acc {:keys [id args] f :fn}]
       (assoc acc id
              (apply f (map (fn [a]
                              (if (contains? acc a)
                                (get acc a)
                                (get-in db (id->path ctx a))))
                            args))))
     {}
     ordered-defs)))

(defn- with-reactions [ctx]
  (assoc ctx ::reactions (compute-reactions ctx (::reaction-defs ctx))))

;; ---- lifecycle ---------------------------------------------------------------

(defn- build-ctx
  "Initialise a domino ctx for the item set implied by `db`, plus eager reactions."
  [form db]
  (-> (d/initialize (live-schema form db) db)
      (assoc ::reaction-defs (all-reaction-defs form db))
      with-reactions))

(defn initialize-ctx
  "Given a form and an initial DB state, build the domino context."
  [form initial-db]
  (build-ctx form initial-db))

(defn- model-of [ctx] (::d/model ctx))

(defn id->path
  "Db path for an id. A vector id (collection item field `[coll idx field]`) IS
   its own path; a keyword field id resolves through the model; otherwise `[id]`."
  [ctx id]
  (cond
    (vector? id) id
    :else        (or (get-in (model-of ctx) [:id->path id]) [id])))

(defn- apply-ops
  "Apply `[[id value] …]` as plain data ops to the db (events are re-fired by the
   subsequent re-init): nil clears/removes a path, anything else sets it."
  [db path-fn changes]
  (reduce (fn [db [id value]]
            (let [path (path-fn id)]
              (if (nil? value)
                (dissoc-in db path)
                (assoc-in db path value))))
          db changes))

(defn transact-ctx
  "Apply `[[id value] …]` changes and rebuild the live ctx from the resulting db."
  [session changes]
  (let [ctx (::ctx session)
        db  (apply-ops (::d/db ctx) (partial id->path ctx) changes)]
    (assoc session ::ctx (build-ctx (:form session) db))))

;; ---- values ------------------------------------------------------------------

(defn get-db [ctx] (::d/db ctx nil))

(defn get-tx-report [ctx] (::d/transaction-report ctx nil))

(defn get-value [ctx id]
  (let [rxns (::reactions ctx)]
    (if (contains? rxns id)
      (get rxns id)
      (some-> ctx get-db (get-in (id->path ctx id))))))

;; ---- field options -----------------------------------------------------------

(defn- field-opts*
  "id -> field opts (with resolved :path) for top-level model fields (collection
   *containers* included; their item fields live in the collection schema and are
   tagged `:item?`, so they are excluded here)."
  [ctx]
  (let [{:keys [id->path id->opts]} (model-of ctx)]
    (into {}
          (keep (fn [[id opts]]
                  (when-not (:item? opts)
                    [id (assoc opts :path (id->path id))])))
          id->opts)))

(defn get-field-opts-fn [ctx]
  (let [opts (field-opts* ctx)]
    (fn [id] (get opts id))))

(defn get-field-opts-map [ctx]
  (field-opts* ctx))

(defn get-fields [session]
  (keys (field-opts* (::ctx session))))

;; ---- collection render data --------------------------------------------------

(defn collections-data
  "Per-collection render data:
   `{coll-id {:order [idx…] :field-opts {fid -> opts} :items {idx {id -> value}}}}`.
   Each item value map carries model fields AND per-item reaction values."
  [session]
  (let [ctx   (::ctx session)
        db    (get-db ctx)
        model (get-in session [:form :data :model])]
    (into {}
          (for [{:keys [id seg schema] :as c} (collection-fields model)]
            (let [fopts   (into {} (map (fn [entry]
                                          (let [fopts (if (map? (second entry)) (second entry) {})]
                                            [(:id fopts) fopts])))
                                (:model schema))
                  rxn-ids (map :id (:reactions schema))]
              [id {:order      (vec (item-indices db c))
                   :field-opts fopts
                   :items      (into {}
                                     (for [idx (item-indices db c)]
                                       [idx (into {}
                                                  (map (fn [fid] [fid (get-value ctx [seg idx fid])]))
                                                  (concat (keys fopts) rxn-ids))]))}])))))

;; ---- relationship graph (for lock expansion) ---------------------------------

(defn- graph-reachable
  "Transitively reachable ids from `start` over the events graph, following edges
   where `start` plays `relationship` (`:input` => downstream, `:output` =>
   upstream). Excludes `start` itself."
  [graph start relationship]
  (loop [seen #{} queue [start]]
    (if-let [id (first queue)]
      (if (contains? seen id)
        (recur seen (subvec queue 1))
        (let [nexts (->> (get graph id)
                         (filter #(= relationship (:relationship %)))
                         (mapcat :connections))]
          (recur (conj seen id) (into (subvec queue 1) nexts))))
      (disj seen start))))

(defn get-related-fn
  "Returns `(fn [id] -> #{related-ids})`: the id plus everything up- and
   downstream of it in the events graph (the set that must be co-locked)."
  [ctx]
  (let [graph (::d/graph ctx)]
    (fn [id]
      (union #{id}
             (graph-reachable graph id :input)
             (graph-reachable graph id :output)))))

(defn get-parents-fn
  "Returns `(fn [id] -> #{ancestor-ids})` — the lock-parents of an id.

   Collection-aware: an item field `[coll idx field]` parents the *item*
   `[coll idx]` (a per-item lock unit), and the item parents nothing — the
   collection container itself is never co-locked, so different items edit
   concurrently. Plain nested fields parent the model ids of their path
   prefixes."
  [ctx]
  (let [{:keys [path->id id->opts]} (model-of ctx)
        coll-segs (into #{} (keep (fn [[id opts]] (when (:collection? opts) id)) id->opts))]
    (fn [id]
      (cond
        ;; [coll idx field] -> the item [coll idx]
        (and (vector? id) (>= (count id) 3) (coll-segs (first id)))
        #{(subvec id 0 2)}
        ;; [coll idx] -> nothing (don't serialise the whole collection)
        (and (vector? id) (= (count id) 2) (coll-segs (first id)))
        #{}
        :else
        (let [path (if (vector? id) id (id->path ctx id))]
          (into #{}
                (keep (fn [n] (get path->id (subvec (vec path) 0 n))))
                (range 1 (count path))))))))
