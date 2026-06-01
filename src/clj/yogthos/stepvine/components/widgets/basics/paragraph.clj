(ns yogthos.stepvine.components.widgets.basics.paragraph
  "Paragraph of body text (re-com's p). Static text, signal-bound text, or — when
   a body is supplied — arbitrary rendered children."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/paragraph
  [ctx _component {:keys [text rxn id default]} body]
  (let [sig-id (or rxn id)
        sig    (when sig-id (if rxn (render/$ sig-id) (render/item-$ ctx sig-id)))
        current (when sig-id
                  (if rxn (get (:rxns ctx) sig-id) (get-in ctx [:values sig-id])))]
    (if (seq body)
      (into [:p.widget.paragraph] (render/render-children ctx body))
      [:p.widget.paragraph
       (cond-> {} sig-id (assoc "data-text" sig))
       (str (if (nil? current) (or default text "") current))])))
