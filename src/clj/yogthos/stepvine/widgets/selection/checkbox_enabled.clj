(ns yogthos.stepvine.widgets.selection.checkbox-enabled
  "Checkbox-enabled text field widget — a checkbox that toggles a paired text input.
   When unchecked, the text field is disabled and cleared."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/checkbox-enabled
  [ctx _component {:keys [id label text-label read-only]} _body]
  (let [sig          (render/item-signal-name ctx id)
        in-item?     (boolean (:item ctx))
        current      (get-in ctx [:values id])
        enabled?     (boolean (:enabled? current))
        text-value   (:value current "")
        checkbox-sig (str sig "_enabled")
        text-sig     (str sig "_value")
        url          (render/field-post-url ctx id)]
    [:div.widget.checkbox-enabled.field
     [:input
      (cond-> {:type      "checkbox"
               "data-bind" checkbox-sig}
        (not in-item?) (assoc :id (str (name id) "-cb") :name (str (name id) "-cb"))
        enabled?       (assoc :checked true)
        read-only      (assoc :disabled true)
        (not read-only)
        (assoc "data-on:change"
               (str "$" checkbox-sig " = !$" checkbox-sig "; "
                    "if (!$" checkbox-sig ") { $" text-sig " = '' }; "
                    "@post('" url "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]
     [:label {:for (when-not in-item? (str (name id) "-cb"))}
      (or label (name id))]
     [:input
      (cond-> {:type        "text"
               :placeholder text-label
               "data-bind"  text-sig}
        (not in-item?) (assoc :id (str (name id) "-txt") :name (str (name id) "-txt"))
        read-only      (assoc :readonly true)
        (not (and enabled? (not read-only)))
        (assoc :disabled true)
        (and enabled? (not read-only))
        (assoc "data-on:input__debounce.300ms" (str "@post('" url "')")
               "data-on:focus" (str "@post('" (render/field-lock-url ctx id) "')")
               "data-on:blur"  (str "@post('" (render/field-unlock-url ctx id) "')")))]]))
