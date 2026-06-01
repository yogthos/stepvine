(ns yogthos.stepvine.widgets.menu
  "Menu widget — button group that sets a value or triggers actions."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/menu
  [ctx _component {:keys [id label options read-only]} _body]
  (let [sig (render/item-signal-name ctx id)
        url (render/field-post-url ctx id)
        opts (or options (get-in ctx [:options id]) [])]
    [:div.widget.menu.field
     [:label label]
     (into [:div.menu-group]
           (for [o opts]
             (let [v (if (vector? o) (second o) o)
                   l (if (vector? o) (first o) o)]
               [:button.menu-btn
                (cond-> {:type  "button"
                         "data-on:click" (str "$" sig " = '" v "'; @post('" url "')")}
                  read-only (assoc :disabled true))
                (str l)])))]))
