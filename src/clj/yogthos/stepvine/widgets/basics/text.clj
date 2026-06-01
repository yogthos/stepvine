(ns yogthos.stepvine.widgets.basics.text
  "Textarea and labeled-value widgets."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

;; --- Textarea -------------------------------------------------------------

(defmethod render-widget :stepvine.components/textarea
  [ctx _component {:keys [id label rows placeholder read-only]} _body]
  (let [sig      (render/item-signal-name ctx id)
        in-item? (boolean (:item ctx))
        value    (get-in ctx [:values id])]
    [:div.widget.textarea.field
     [:label {:for (when-not in-item? (name id))}
      (or label (name id))]
     [:textarea
      (cond-> (merge {:placeholder (or placeholder "")}
                     (when rows {:rows rows}))
        true              (assoc "data-bind" sig)
        (not in-item?)    (assoc :id (name id) :name (name id))
        (not read-only)
        (assoc "data-on:input__debounce.300ms" (str "@post('" (render/field-post-url ctx id) "')")
               "data-on:focus" (str "@post('" (render/field-lock-url ctx id) "')")
               "data-on:blur"  (str "@post('" (render/field-unlock-url ctx id) "')")
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))
      (str (if (nil? value) "" value))]]))

;; --- Labeled value --------------------------------------------------------

(defmethod render-widget :stepvine.components/labeled-value
  [ctx _component {:keys [id label default]} _body]
  (let [sig     (render/item-$ ctx id)
        current (get-in ctx [:values id])]
    [:div.widget.labeled-value.field
     [:label label]
     [:span {"data-text" sig}
      (str (if (nil? current) (or default "") current))]]))
