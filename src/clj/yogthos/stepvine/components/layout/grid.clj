(ns yogthos.stepvine.components.layout.grid
  "Responsive grid layout (parity stepvine-9by). `:c/grid` lays its children out
   in an N-column CSS grid that collapses to a single column on narrow screens.
   Each direct child may carry a `:span` (1–N) to occupy several columns —
   stepvine's take on ibis' Bootstrap `:row`/`:columns`/`:column-width`.

     [:c/grid {:cols 2 :gap :md}
      [:c/input-field {:id :first :label \"First\"}]
      [:c/input-field {:id :last  :label \"Last\"}]
      [:c/input-field {:id :notes :label \"Notes\" :span 2}]]"
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defn- cell
  "Wrap one child in a grid cell, lifting its `:span` to a `span-N` class so the
   span never leaks onto the inner widget as a DOM attribute."
  [ctx node]
  (let [attrs (when (and (vector? node) (map? (second node))) (second node))
        span  (:span attrs)
        node  (cond-> node span (update 1 dissoc :span))]
    [:div {:class (cond-> "grid-cell" span (str " span-" span))}
     (render/render-node ctx node)]))

(defmethod render-widget :stepvine.components/grid
  [ctx _component {:keys [cols gap class]} body]
  (into [:div {:class (->> ["widget grid"
                            (str "cols-" (or cols 2))
                            (when gap (str "gap-" (name gap)))
                            class]
                           (remove nil?)
                           (str/join " "))}]
        (map (partial cell ctx) body)))
