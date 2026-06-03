(ns yogthos.stepvine.components.widgets.selection.checkbox
  "Checkbox widget — boolean toggle with label."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
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
        (assoc "data-on:change" (str "@post('" (endpoints/field-post-url ctx id) "')")
               "data-on:focus"  (str "@post('" (endpoints/field-lock-url ctx id) "')")
               "data-on:blur"   (str "@post('" (endpoints/field-unlock-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]
     [:label {:for (when-not in-item? (name id))} (or label (name id))]]))
