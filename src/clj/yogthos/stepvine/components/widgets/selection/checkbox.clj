(ns yogthos.stepvine.components.widgets.selection.checkbox
  "Checkbox widget — boolean toggle with label."
  (:require
   [yogthos.stepvine.components.bind :as bind]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/checkbox
  [ctx _component {:keys [id label read-only]} _body]
  (let [sig      (signals/item-signal-name ctx id)
        in-item? (boolean (:item ctx))
        checked? (boolean (get-in ctx [:values id]))]
    [:div.widget.checkbox.field
     [:input
      (cond-> {:type  "checkbox"
               "data-bind" sig}
        (not in-item?) (assoc :id (name id) :name (name id))
        checked?       (assoc :checked true)   ; reflect the persisted value
        read-only      (assoc :disabled true)
        (not read-only)
        (merge (bind/edit-bind-attrs ctx id sig "data-on:change")))]
     [:label {:for (when-not in-item? (name id))} (or label (name id))]]))
