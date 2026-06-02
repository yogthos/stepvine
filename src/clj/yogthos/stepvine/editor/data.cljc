(ns yogthos.stepvine.editor.data
  (:require [domino.core :as d]
            [clojure.string :as str]
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

(defn- item-indices
  "The item index keys of a collection in `db`. Indices are signal-safe strings
   (see session/new-index); filtering to strings keeps any non-item bookkeeping
   keys (e.g. a stored :order vector) out of the item set."
  [db {:keys [seg]}]
  (filter string? (keys (get db seg))))

(defn- path->flat-id
  "Synthetic FLAT keyword id for a field at db `path`. domino's schema validation
   flattens event input/output ids, so compound (vector) ids are rejected; the
   model keeps the nested path while the id is a collision-free keyword joining
   the path segments (e.g. [:teams \"t1\" :members \"m1\" :first] ->
   :teams__t1__members__m1__first)."
  [path]
  (keyword (str/join "__" (map #(if (keyword? %) (name %) (str %)) path))))

(defn- nested-entry
  "Fully-nested model tuple for a single leaf field at db `path` carrying `opts`.
   Branch segments carry no opts map — domino validates the raw model by
   flattening it, and a map without `:id` is treated as invalid; only the leaf
   carries the opts (with its `:id`)."
  [path opts]
  (reduce (fn [child seg] [seg child])
          [(last path) opts]
          (reverse (butlast path))))

(defn- expand-item
  "Recursively expand a collection item's `schema` at db-path `prefix` (db subtree
   `sub`) into {:model :events :reaction-defs}. Item fields project onto nested
   paths with flat ids (tagged `:item?`); events are rewritten to those ids and
   bridged so the form handler still sees/returns local field ids; reactions keep
   vector (path) ids for editor-side computation. Nested collections in the item
   schema are expanded recursively for the items present in `sub`."
  [prefix schema sub]
  (let [{:keys [model events reactions]} schema
        colls     (collection-fields model)
        coll-segs (set (map :seg colls))
        plain     (remove #(coll-segs (first %)) model)
        fid       (fn [local] (path->flat-id (conj (vec prefix) local)))
        models    (mapv (fn [entry]
                          (let [fseg  (first entry)
                                fopts (if (map? (second entry)) (second entry) {})]
                            (nested-entry (conj (vec prefix) fseg)
                                          (assoc fopts :id (fid (:id fopts)) :item? true))))
                        plain)
        evs       (mapv (fn [{:keys [inputs outputs] h :handler}]
                          {:inputs  (mapv fid inputs)
                           :outputs (mapv fid outputs)
                           :handler (fn [_ctx in _out]
                                      (let [local (into {} (map (fn [i] [i (get in (fid i))])) inputs)]
                                        (into {} (map (fn [[k v]] [(fid k) v])) (h {:inputs local}))))})
                        events)
        rds       (mapv (fn [{:keys [id args] f :fn}]
                          {:id   (conj (vec prefix) id)
                           :args (mapv (fn [a] (conj (vec prefix) a)) args)
                           :fn   f})
                        reactions)
        children  (for [{:keys [seg schema]} colls
                        cidx (filter string? (keys (get sub seg)))]
                    (expand-item (conj (vec prefix) seg cidx) schema (get-in sub [seg cidx])))]
    {:model         (into models (mapcat :model children))
     :events        (into evs (mapcat :events children))
     :reaction-defs (into rds (mapcat :reaction-defs children))}))

(defn- expansions
  "Expand every item of every top-level collection in `db` (recursing into nested
   collections)."
  [form db]
  (for [{:keys [seg schema] :as c} (collection-fields (get-in form [:data :model]))
        idx (item-indices db c)]
    (expand-item [seg idx] schema (get-in db [seg idx]))))

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
  "Initialise a domino ctx for the item set implied by `db`, plus eager reactions.
   The per-item (recursive) expansion is computed once and feeds both the live
   schema and the reaction defs."
  [form db]
  (let [{:keys [model events reactions]} (:data form)
        exps   (expansions form db)
        schema {:model  (into (vec model) (mapcat :model exps))
                :events (into (mapv bridge-event events) (mapcat :events exps))}]
    (-> (d/initialize schema db)
        (assoc ::reaction-defs (topo-reactions (into (vec reactions) (mapcat :reaction-defs exps))))
        with-reactions)))

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
  "Apply `[[id value] …]` as plain data ops to the db: nil clears/removes a path,
   anything else sets it. Used to compute the post-change db for the structural
   check and to seed a rebuild."
  [db path-fn changes]
  (reduce (fn [db [id value]]
            (let [path (path-fn id)]
              (if (nil? value)
                (dissoc-in db path)
                (assoc-in db path value))))
          db changes))

(defn- coll-signature
  "The nested item-index structure of `db` for `model`. Changes exactly when a
   collection item is added or removed at any level — i.e. when the live (per-item)
   schema would differ — so it tells `transact-ctx` whether it must rebuild or can
   transact incrementally."
  [model db]
  (into {}
        (for [{:keys [seg schema] :as c} (collection-fields model)]
          [seg (into {} (for [idx (item-indices db c)]
                          [idx (coll-signature (:model schema) (get-in db [seg idx]))]))])))

(defn transact-ctx
  "Apply `[[id value] …]` changes to the live ctx.

   Value edits go through domino's incremental `transact` — events fire on the
   paths that actually CHANGED (not every present input), effects run, and the
   engine returns a change report. Only a STRUCTURAL change (a collection item
   added/removed, which alters the per-item schema) falls back to a full rebuild,
   since domino has no subcontexts. Eager reactions are recomputed either way."
  [session changes]
  (let [ctx     (::ctx session)
        path-fn (partial id->path ctx)
        model   (get-in session [:form :data :model])
        new-db  (apply-ops (::d/db ctx) path-fn changes)]
    (assoc session ::ctx
           (if (and (seq (collection-fields model))
                    (not= (coll-signature model (::d/db ctx))
                          (coll-signature model new-db)))
             (build-ctx (:form session) new-db)                          ; item set changed
             (-> (d/transact ctx (mapv (fn [[id v]] [(path-fn id) v]) changes))
                 with-reactions)))))

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
               [id {:order      (let [ks (vec (item-indices db c))
                                   explicit (get-in db [seg :order])]
                              (if explicit
                                (filterv (set ks) (into explicit ks))
                                ks))
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
