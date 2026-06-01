(ns yogthos.stepvine.widgets.checkbox
  "Checkbox widget — boolean toggle with label."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/checkbox
  [ctx _component {:keys [id label read-only]} _body]
  (let [sig      (render/item-signal-name ctx id)
        in-item? (boolean (:item ctx))]
    [:div.widget.checkbox.field
     [:input
      (cond-> {:type  "checkbox"
               "data-bind" sig}
        (not in-item?) (assoc :id (name id) :name (name id))
        read-only      (assoc :disabled true)
        (not read-only)
        (assoc "data-on:change" (str "@post('" (render/field-post-url ctx id) "')")
               "data-on:focus"  (str "@post('" (render/field-lock-url ctx id) "')")
               "data-on:blur"   (str "@post('" (render/field-unlock-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]
     [:label {:for (when-not in-item? (name id))} (or label (name id))]]))
