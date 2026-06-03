(ns yogthos.stepvine.view-state
  "Table view-state: presentation-only sort / filter / page / row-order / column
   overlay, per document and shared by all viewers.

   These are NOT document data — Domino has no concept of them — so they live on
   the session map under `:view-state` and survive transacts (a change only
   touches the engine's ::ctx). They are updated directly on the session atom (no
   Domino transact); the route handler re-renders the affected collection and
   broadcasts. The table widget READS this shape back from the render context via
   `signals/collections-data`, which applies the stored row `:order`.

   Shape (ARCHITECTURE.md invariant #4):

     :view-state {coll-id {:sort   {:col <kw> :dir :asc|:desc}   ; nil = unsorted
                           :filter {:col <kw> :value <str>}      ; absent = all rows
                           :page   <int>                         ; 0-based
                           :order  [idx …]                       ; row drag order
                           :cols   {:order  [path …]             ; column reorder
                                    :hidden #{path …}            ; removed columns
                                    :labels {path <str>}}}}      ; relabeled headers

   Built on the session primitives (`session/item-keys`, the session atom) — see
   `session.clj` for the live-editing layer this sits on top of."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.editor :as e]
   [yogthos.stepvine.session :as session]))

(defn- update-view!
  [manager doc-id coll-id f]
  (swap! (e/get-session-atom! manager doc-id)
         update-in [:view-state coll-id] (fnil f {})))

(defn move-item!
  "Reorder a collection by moving item key `from-key` to before `to-key`. Stores
   the resulting order in view-state; collections-data renders by it."
  [manager doc-id coll-id from-key to-key]
  (when (and from-key to-key (not= from-key to-key))
    (let [base (set (session/item-keys manager doc-id coll-id))]
      (when (and (base from-key) (base to-key))
        (update-view! manager doc-id coll-id
                      (fn [{:keys [order] :as vs}]
                        (let [cur (vec (filter base (or (not-empty order) (session/item-keys manager doc-id coll-id))))
                              cur (vec (remove #{from-key} cur))
                              ti  (.indexOf cur to-key)
                              ti  (if (neg? ti) (count cur) ti)]
                          (assoc vs :order (vec (concat (subvec cur 0 ti) [from-key] (subvec cur ti)))))))))))

(defn set-table-sort!
  "Cycle the sort for a column: unsorted → asc → desc → unsorted."
  [manager doc-id coll-id col]
  (let [col (keyword col)]
    (update-view! manager doc-id coll-id
                  (fn [{:keys [sort] :as vs}]
                    (assoc vs :sort
                           (cond
                             (not= (:col sort) col) {:col col :dir :asc}
                             (= (:dir sort) :asc)   {:col col :dir :desc}
                             :else                  nil))))))

(defn set-table-filter!
  "Set the table's row filter (view-state) to `{:col <col> :value <v>}`. A blank
   value clears the filter (all rows shown). View-only — no document change."
  [manager doc-id coll-id col value]
  (update-view! manager doc-id coll-id
                (fn [vs]
                  (if (str/blank? (str value))
                    (dissoc vs :filter)
                    (assoc vs :filter {:col (keyword col) :value (str value)})))))

;; --- Table column customization (view-state overlay: order/hidden/labels) ---

(defn set-table-column-order!
  "Persist a column display order (a vector of column path keywords)."
  [manager doc-id coll-id paths]
  (let [order (mapv keyword paths)]
    (update-view! manager doc-id coll-id #(assoc-in % [:cols :order] order))))

(defn hide-table-column!
  "Hide a column from the table display (view-only; the field/data is untouched)."
  [manager doc-id coll-id path]
  (update-view! manager doc-id coll-id
                #(update-in % [:cols :hidden] (fnil conj #{}) (keyword path))))

(defn restore-table-column!
  "Un-hide the most-recently-hidden column (the inverse of hide); no-op if none."
  [manager doc-id coll-id]
  (update-view! manager doc-id coll-id
                (fn [vs]
                  (let [hidden (get-in vs [:cols :hidden])]
                    (if (seq hidden)
                      (assoc-in vs [:cols :hidden] (disj hidden (last (vec hidden))))
                      vs)))))

(defn set-table-column-label!
  "Override (or clear, on blank) a column's display label."
  [manager doc-id coll-id path label]
  (update-view! manager doc-id coll-id
                (fn [vs]
                  (if (str/blank? (str label))
                    (update-in vs [:cols :labels] dissoc (keyword path))
                    (assoc-in vs [:cols :labels (keyword path)] (str label))))))

(defn set-table-page!
  "Move the table page: dir is \"next\"/\"prev\" or an absolute integer string."
  [manager doc-id coll-id dir]
  (update-view! manager doc-id coll-id
                (fn [{:keys [page] :or {page 0} :as vs}]
                  (assoc vs :page (max 0 (case dir
                                           "next" (inc page)
                                           "prev" (dec page)
                                           (or (parse-long (str dir)) page)))))))
