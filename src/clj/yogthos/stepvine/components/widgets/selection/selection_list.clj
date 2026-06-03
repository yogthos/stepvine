(ns yogthos.stepvine.components.widgets.selection.selection-list
  "Flat multi-check list (re-com's selection-list). A scrollable list of options,
   each a checkbox bound to an :array field signal. Selected state is reactive
   over the signal (data-attr:checked), so it stays in sync across clients."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
   [yogthos.stepvine.render :refer [render-widget]]
   [yogthos.stepvine.components.widgets.selection.array-signal :as arr]))

(defn- option-pair [o]
  (if (vector? o) [(first o) (second o)] [o o]))

(defmethod render-widget :stepvine.components/selection-list
  [ctx _component {:keys [id label options read-only]} _body]
  (let [sig     (signals/item-signal-name ctx id)
        current (get-in ctx [:values id])
        sel     (set (when (sequential? current) current))
        opts    (or options (get-in ctx [:options id]) [])
        url     (endpoints/field-post-url ctx id)]
    [:div.widget.selection-list.field
     (when label [:label label])
     (into [:ul.selection-list-items {:role "listbox" "aria-multiselectable" "true"}]
           (for [o opts]
             (let [[l v] (option-pair o)]
               [:li.selection-list-item {:role "option"}
                [:label
                 [:input
                  (cond-> {:type "checkbox"
                           ;; SSR initial state + reactive sync over the signal
                           :checked (contains? sel v)
                           "data-attr:checked" (arr/includes-expr sig v)}
                    read-only       (assoc :disabled true)
                    (not read-only) (assoc "data-on:change" (arr/toggle-expr sig v url)))]
                 [:span (str l)]]])))]))
