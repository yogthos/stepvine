(ns yogthos.stepvine.widgets.date-picker
  "Date input widget."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/date-picker
  [ctx _component {:keys [id label min max placeholder read-only]} _body]
  (let [sig      (render/item-signal-name ctx id)
        in-item? (boolean (:item ctx))]
    [:div.widget.date-picker.field
     [:label {:for (when-not in-item? (name id))}
      (or label (name id))]
     [:input
      (cond-> {:type  "date"
               "data-bind" sig
               :placeholder (or placeholder "yyyy-mm-dd")}
        (not in-item?) (assoc :id (name id) :name (name id))
        min             (assoc :min min)
        max             (assoc :max max)
        read-only       (assoc :readonly true)
        (not read-only)
        (assoc "data-on:change" (str "@post('" (render/field-post-url ctx id) "')")
               "data-on:focus"  (str "@post('" (render/field-lock-url ctx id) "')")
               "data-on:blur"   (str "@post('" (render/field-unlock-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]]))
