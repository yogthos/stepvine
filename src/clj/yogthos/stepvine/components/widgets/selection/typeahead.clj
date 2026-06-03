(ns yogthos.stepvine.components.widgets.selection.typeahead
  "Typeahead/autocomplete widget using HTML5 datalist."
  (:require
   [yogthos.stepvine.components.bind :as bind]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/typeahead
  [ctx _component {:keys [id label options placeholder read-only]} _body]
  (let [sig      (signals/item-signal-name ctx id)
        in-item? (boolean (:item ctx))
        opts     (or options (get-in ctx [:options id]) [])
        list-id  (str (name id) "-list")]
    [:div.widget.typeahead.field
     [:label {:for (when-not in-item? (name id))}
      (or label (name id))]
     [:input
      (cond-> {:type        "text"
               :list        list-id
               :placeholder (or placeholder "")
               "data-bind"  sig}
        (not in-item?) (assoc :id (name id) :name (name id))
        read-only       (assoc :readonly true)
        (not read-only)
        (merge (bind/edit-bind-attrs ctx id sig "data-on:input__debounce.200ms")))]
     [:datalist {:id list-id}
      (for [o opts]
        (let [v (if (vector? o) (str (second o)) (str o))]
          [:option {:value v}]))]]))
