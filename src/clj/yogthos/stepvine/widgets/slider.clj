(ns yogthos.stepvine.widgets.slider
  "Range slider widget with value display."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/slider
  [ctx _component {:keys [id label min max step read-only]} _body]
  (let [sig      (render/item-signal-name ctx id)
        in-item? (boolean (:item ctx))
        current  (get-in ctx [:values id])]
    [:div.widget.slider.field
     [:label {:for (when-not in-item? (name id))}
      (or label (name id))]
     [:input
      (cond-> {:type  "range"
               :min   (str (or min 0))
               :max   (str (or max 100))
               :step  (str (or step 1))
               "data-bind" sig}
        (not in-item?) (assoc :id (name id) :name (name id))
        read-only      (assoc :disabled true)
        (not read-only)
        (assoc "data-on:input__debounce.100ms" (str "@post('" (render/field-post-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]
     [:span.slider-value {"data-text" (render/item-$ ctx id)}
      (str (or current (or min 0)))]]))
