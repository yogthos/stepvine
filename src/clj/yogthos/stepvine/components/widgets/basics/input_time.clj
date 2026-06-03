(ns yogthos.stepvine.components.widgets.basics.input-time
  "Time input widget using HTML5 type=\"time\"."
  (:require
   [yogthos.stepvine.components.bind :as bind]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/input-time
  [ctx _component {:keys [id label placeholder read-only]} _body]
  (let [sig      (signals/item-signal-name ctx id)
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
        (merge (bind/edit-bind-attrs ctx id sig "data-on:input__debounce.300ms")))]]))
