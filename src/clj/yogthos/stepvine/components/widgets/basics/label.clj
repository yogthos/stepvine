(ns yogthos.stepvine.components.widgets.basics.label
  "Static label widget — displays text, optionally bound to a signal."
  (:require
   [yogthos.stepvine.format :as fmt]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/label
  [ctx _component {:keys [text rxn id default fmt]} _body]
  (let [sig-id (or rxn id)
        sig    (when sig-id
                 (if rxn (render/$ sig-id) (render/item-$ ctx sig-id)))
        current (when sig-id
                  (if rxn
                    (get (:rxns ctx) sig-id)
                    (get-in ctx [:values sig-id])))]
    [:span.widget.label
     (cond-> {}
       sig-id (assoc "data-text" (fmt/fmt-text-expr fmt sig)))   ; :fmt printf formatting
     (str (if (nil? current) (or default text "") (fmt/fmt-value fmt current)))]))
