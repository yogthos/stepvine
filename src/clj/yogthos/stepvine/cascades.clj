(ns yogthos.stepvine.cascades
  "Cascading dropdowns, compiled into the Domino event DAG (parity stepvine-9wo).

   A dropdown that `:depends-on` a parent field narrows its options to the
   parent's value. When the parent changes the child's old choice is invalid, so
   it must clear — and if the child is itself a parent, ITS child must clear too,
   and so on. We add one Domino *event* per dependency: `parent -> child := \"\"`.
   Because the editor now drives changes through `domino.core/transact`, events
   fire on the paths that actually CHANGED (not every present input), so a
   clearing event does NOT pin its child — it fires only when the parent moves.
   Domino then propagates the whole chain in a single transaction, topologically
   (clearing the child fires the grandchild's clearing event, …): the engine's DAG
   does the cascade, to any depth.

   Handlers are emitted as quoted forms so they survive the sci round-trip in
   `editor.impl/eval-form` (like the compiled validation reactions)."
  (:require
   [clojure.walk :as walk]))

(defn- dropdown?
  "Does widget keyword `kw` resolve (via the view's aliases) to a dropdown widget?"
  [aliases kw]
  (let [r (if-let [full (get aliases (namespace kw))] (keyword full (name kw)) kw)]
    (and (= "stepvine.components" (namespace r))
         (#{"dropdown" "dropdown-select"} (name r)))))

(defn- view-pairs
  "[{:parent :child} …] for every dependent dropdown in one view's markup."
  [view]
  (let [aliases (get-in view [:opts :widget-namespaces])
        pairs   (atom [])]
    (walk/prewalk
     (fn [n]
       (when (and (vector? n) (keyword? (first n)) (namespace (first n))
                  (map? (second n))
                  (dropdown? aliases (first n))
                  (:depends-on (second n)) (:id (second n)))
         (swap! pairs conj {:parent (keyword (:depends-on (second n)))
                            :child  (keyword (:id (second n)))}))
       n)
     (:markup view))
    @pairs))

(defn cascade-pairs
  "Distinct {:parent :child} dependencies declared by `:depends-on` dropdowns
   across all of a form's views."
  [form]
  (distinct (mapcat view-pairs (vals (:views form)))))

(defn compile-cascades
  "Add a Domino clearing event for each cascading-dropdown dependency, so changing
   a parent clears its child (and the rest of the chain) via the event DAG. No-op
   when no dropdown declares `:depends-on`."
  [form]
  (let [pairs (cascade-pairs form)]
    (cond-> form
      (seq pairs)
      (update-in [:data :events] (fnil into [])
                 (map (fn [{:keys [parent child]}]
                        {:id      (keyword (str "cascade-clear-" (name child)))
                         :inputs  [parent]
                         :outputs [child]
                         :handler (list 'fn ['_] {child ""})})
                      pairs)))))
