(ns yogthos.stepvine.components.widgets.basics.value
  "Inline signal-bound text — renders a field or reaction value as a <span>."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.util/value
  [ctx _component {:keys [rxn id default]} _body]
  ;; Bind text to a signal. Inside a collection item both :rxn and :id are
  ;; item-scoped and read from the item's value map (which carries per-item
  ;; reaction values); at top level :rxn reads reactions and :id reads fields.
  (let [sig-id   (or rxn id)
        in-item? (boolean (:item ctx))
        sig      (if in-item? (render/item-$ ctx sig-id) (if rxn (render/$ rxn) (render/$ id)))
        current  (if in-item?
                   (get (:values ctx) sig-id)
                   (get (if rxn (:rxns ctx) (:values ctx)) sig-id))]
    [:span {"data-text" sig}
     (str (if (nil? current) (or default "") current))]))
