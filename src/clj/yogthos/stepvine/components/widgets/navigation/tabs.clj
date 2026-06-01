(ns yogthos.stepvine.components.widgets.navigation.tabs
  "Tab selector (re-com's horizontal-tabs / bar / pill variants). A single-select
   control bound to a top-level signal: clicking a tab sets the field to that
   tab's id and POSTs. Render the per-tab content separately with :show keyed on
   the same signal — matching re-com, where the tab strip is just the selector."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/tabs
  [ctx _component {:keys [id label tabs variant read-only]} _body]
  (let [sig     (render/item-signal-name ctx id)
        current (get-in ctx [:values id])
        url     (render/field-post-url ctx id)
        variant (name (or variant :horizontal))]
    [:div.widget.tabs.field
     (when label [:label label])
     (into [:div {:class (str "tabs-strip tabs-" variant) :role "tablist"}]
           (for [t tabs]
             (let [v (if (vector? t) (second t) (:id t))
                   l (if (vector? t) (first t) (:label t))
                   selected? (= v current)]
               [:button
                (cond-> {:class (str "tab-btn" (when selected? " selected"))
                         :type  "button"
                         :role  "tab"
                         "aria-selected" (str (boolean selected?))}
                  read-only       (assoc :disabled true)
                  (not read-only) (assoc "data-on:click"
                                         (str "$" sig " = '" v "'; @post('" url "')")))
                (str l)])))]))
