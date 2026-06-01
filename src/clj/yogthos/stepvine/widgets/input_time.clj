(ns yogthos.stepvine.widgets.input-time
  "Time input widget using HTML5 type=\"time\"."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/input-time
  [ctx _component {:keys [id label placeholder read-only]} _body]
  (let [sig      (render/item-signal-name ctx id)
        in-item? (boolean (:item ctx))]
    [:div.widget.input-time.field
     [:label {:for (when-not in-item? (name id))}
      (or label (name id))]
     [:input
      (cond-> {:type  "time"
               "data-bind" sig
               :placeholder (or placeholder "HH:MM")}
        (not in-item?) (assoc :id (name id) :name (name id))
        read-only      (assoc :readonly true)
        (not read-only)
        (assoc "data-on:input__debounce.300ms" (str "@post('" (render/field-post-url ctx id) "')")
               "data-on:focus" (str "@post('" (render/field-lock-url ctx id) "')")
               "data-on:blur"  (str "@post('" (render/field-unlock-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]]))
