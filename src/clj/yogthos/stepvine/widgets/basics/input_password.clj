(ns yogthos.stepvine.widgets.basics.input-password
  "Password input — two-way bound, server-authoritative locking. Structurally a
   masked variant of :input-field (re-com's input-password)."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/input-password
  [ctx _component {:keys [id label read-only]} _body]
  (let [opts     (get-in ctx [:field-opts id])
        nm       (name id)
        in-item? (boolean (:item ctx))
        sig      (render/item-signal-name ctx id)
        value    (get-in ctx [:values id])]
    [:div.field
     [:label label]
     [:input.widget.input-password
      (cond-> {:type  "password"
               :value (if (nil? value) "" (str value))}
        true              (assoc "data-bind" sig)
        (not in-item?)    (assoc :id nm :name nm)
        (:required? opts) (assoc :required true)
        read-only         (assoc :readonly true)
        (not read-only)
        (assoc "data-on:input__debounce.300ms" (str "@post('" (render/field-post-url ctx id) "')")
               "data-on:focus" (str "@post('" (render/field-lock-url ctx id) "')")
               "data-on:blur"  (str "@post('" (render/field-unlock-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]]))
