(ns yogthos.stepvine.components.widgets.selection.multi-select
  "Dual list-box (re-com's multi-select). Two columns — Available and Selected —
   bound to an :array field signal. Every option is rendered in BOTH columns and
   shown in exactly one via reactive data-show over the signal, so items appear
   to move between columns with no element re-render (field changes broadcast
   signals only). Clicking an available item adds it; a selected item removes it."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
   [yogthos.stepvine.render :refer [render-widget]]
   [yogthos.stepvine.components.widgets.selection.array-signal :as arr]))

(defn- option-pair [o]
  (if (vector? o) [(first o) (second o)] [o o]))

(defn- column [title items]
  (into [:div.multi-select-col
         [:div.multi-select-col-title title]]
        items))

(defmethod render-widget :stepvine.components/multi-select
  [ctx _component {:keys [id label options read-only left-title right-title]} _body]
  (let [sig     (signals/item-signal-name ctx id)
        current (get-in ctx [:values id])
        sel     (set (when (sequential? current) current))
        opts    (or options (get-in ctx [:options id]) [])
        url     (endpoints/field-post-url ctx id)
        item    (fn [side l v]
                  [:button.multi-select-item
                   (cond-> {:type "button"
                            ;; show in Available when NOT selected, Selected when selected
                            "data-show" (cond->> (arr/includes-expr sig v)
                                          (= side :available) (str "!"))}
                     ;; SSR initial visibility (datastar takes over after hydration)
                     (and (= side :available) (contains? sel v)) (assoc :hidden true)
                     (and (= side :selected)  (not (contains? sel v))) (assoc :hidden true)
                     read-only       (assoc :disabled true)
                     (and (not read-only) (= side :available))
                     (assoc "data-on:click" (arr/add-expr sig v url))
                     (and (not read-only) (= side :selected))
                     (assoc "data-on:click" (arr/remove-expr sig v url)))
                   (str l)])]
    [:div.widget.multi-select.field
     (when label [:label label])
     [:div.multi-select-cols
      (column (or left-title "Available")
              (for [o opts] (let [[l v] (option-pair o)] (item :available l v))))
      (column (or right-title "Selected")
              (for [o opts] (let [[l v] (option-pair o)] (item :selected l v))))]]))
