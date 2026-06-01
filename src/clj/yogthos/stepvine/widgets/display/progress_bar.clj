(ns yogthos.stepvine.widgets.display.progress-bar
  "Progress bar (re-com's progress-bar). The fill width tracks a numeric signal
   (0–100) — bound to a field :id or a reaction :rxn — and updates live over SSE."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/progress-bar
  [ctx _component {:keys [id rxn label striped?]} _body]
  (let [sig-id  (or rxn id)
        sig     (if rxn (render/$ sig-id) (render/item-$ ctx sig-id))
        current (if rxn (get (:rxns ctx) sig-id) (get-in ctx [:values sig-id]))
        pct     (or current 0)]
    [:div.widget.progress-bar.field
     (when label [:label label])
     [:div.progress-track
      [:div.progress-fill
       {:class (when striped? "progress-striped")
        :style (str "width: " pct "%;")
        "data-attr:style" (str "'width: ' + (" sig " || 0) + '%'")}
       [:span.progress-label
        {"data-text" (str "(" sig " || 0) + '%'")}
        (str pct "%")]]]]))
