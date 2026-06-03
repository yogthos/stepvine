(ns yogthos.stepvine.components.widgets.selection.radio
  "Radio button group widget."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
   [yogthos.stepvine.render :refer [render-widget]]))

(defn- option-value [option]
  (if (and (vector? option) (= 2 (count option)))
    (second option)
    option))

(defn- option-label [option]
  (if (and (vector? option) (= 2 (count option)))
    (first option)
    option))

(defmethod render-widget :stepvine.components/radio
  [ctx _component {:keys [id label options read-only stacked]} _body]
  (let [sig      (signals/item-signal-name ctx id)
        in-item? (boolean (:item ctx))
        current  (get-in ctx [:values id])
        opts     (or options (get-in ctx [:options id]) [])]
    [:div.widget.radio.field
     (when label [:label label])
     (into [:div.radio-group {:class (when stacked "radio-stacked")}]
           (for [o opts]
             (let [v (option-value o)
                   l (option-label o)
                   checked (= v current)]
               [:label.radio-option
                [:input
                 (cond-> {:type  "radio"
                          :value (str v)
                          :name  (name id)
                          "data-bind" sig}
                   checked   (assoc :checked true)
                   read-only (assoc :disabled true)
                   (not read-only)
                   (assoc "data-on:change" (str "@post('" (endpoints/field-post-url ctx id) "')")))]
                (str l)])))]))
