(ns yogthos.stepvine.components.widgets.basics.title
  "Heading widget (re-com's title). Static text or signal-bound; :level picks the
   heading element (h1–h4, default h2)."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/title
  [ctx _component {:keys [text rxn id level underline? default]} _body]
  (let [tag    (keyword (str "h" (min 4 (max 1 (or level 2)))))
        sig-id (or rxn id)
        sig    (when sig-id (if rxn (render/$ sig-id) (render/item-$ ctx sig-id)))
        current (when sig-id
                  (if rxn (get (:rxns ctx) sig-id) (get-in ctx [:values sig-id])))]
    [tag
     (cond-> {:class (str "widget title" (when underline? " title-underline"))}
       sig-id (assoc "data-text" sig))
     (str (if (nil? current) (or default text "") current))]))
