(ns yogthos.stepvine.components.widgets.selection.selections
  "Button-based selection widget (single or multi)."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/selections
  [ctx _component {:keys [id label options multi? read-only]} _body]
  (let [sig     (signals/item-signal-name ctx id)
        current (get-in ctx [:values id])
        opts    (or options (get-in ctx [:options id]) [])
        url     (endpoints/field-post-url ctx id)]
    [:div.widget.selections.field
     [:label label]
     (into [:div.selections-group]
           (for [o opts]
             (let [v     (if (vector? o) (second o) o)
                   l     (if (vector? o) (first o) o)
                   selected? (if multi?
                               (boolean (some #(= v %) current))
                               (= v current))]
               [:button.selection-btn
                (cond-> {:class (when selected? "selected")
                         :type  "button"}
                  read-only (assoc :disabled true)
                  (not read-only)
                  (assoc "data-on:click"
                         (if multi?
                           ;; toggle value in array: if included → remove, else → add
                           (str "$" sig " = $" sig "?.includes('" v "') ? $" sig ".filter(x => x !== '" v "') : ($" sig " || []).concat('" v "'); @post('" url "')")
                           (str "$" sig " = '" v "'; @post('" url "')"))))
                (str l)])))]))
