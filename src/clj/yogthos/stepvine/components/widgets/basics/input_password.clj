(ns yogthos.stepvine.components.widgets.basics.input-password
  "Password input — two-way bound, server-authoritative locking. Structurally a
   masked variant of :input-field (re-com's input-password)."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/input-password
  [ctx _component {:keys [id label read-only]} _body]
  (let [opts     (get-in ctx [:field-opts id])
        nm       (name id)
        in-item? (boolean (:item ctx))
        sig      (signals/item-signal-name ctx id)
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
        (assoc "data-on:input__debounce.300ms" (str "@post('" (endpoints/field-post-url ctx id) "')")
               "data-on:focus" (str "@post('" (endpoints/field-lock-url ctx id) "')")
               "data-on:blur"  (str "@post('" (endpoints/field-unlock-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]]))
