(ns yogthos.stepvine.components.widgets.basics.text
  "Textarea and labeled-value widgets."
  (:require
   [yogthos.stepvine.components.bind :as bind]
   [yogthos.stepvine.format :as fmt]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

;; --- Textarea -------------------------------------------------------------

(defmethod render-widget :stepvine.components/textarea
  [ctx _component {:keys [id label rows placeholder read-only]} _body]
  (let [sig      (signals/item-signal-name ctx id)
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
        (merge (bind/edit-bind-attrs ctx id sig "data-on:input__debounce.300ms")))
      (str (if (nil? value) "" value))]]))

;; --- Labeled value --------------------------------------------------------

(defmethod render-widget :stepvine.components/labeled-value
  [ctx _component {:keys [id label default fmt]} _body]
  (let [sig     (signals/item-$ ctx id)
        current (get-in ctx [:values id])]
    [:div.widget.labeled-value.field (when (and id (not (:item ctx))) {:id (str "lv-" (name id))})
     [:label label]
     [:span {"data-text" (fmt/fmt-text-expr fmt sig)}     ; :fmt formats live + on render
      (str (if (nil? current) (or default "") (fmt/fmt-value fmt current)))]]))
