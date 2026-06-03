(ns yogthos.stepvine.components.widgets.basics.value
  "Inline signal-bound text — renders a field or reaction value as a <span>."
  (:require
   [yogthos.stepvine.format :as fmt]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/value
  [ctx _component {:keys [rxn id default fmt]} _body]
  ;; Bind text to a signal. Inside a collection item both :rxn and :id are
  ;; item-scoped and read from the item's value map (which carries per-item
  ;; reaction values); at top level :rxn reads reactions and :id reads fields.
  ;; `:fmt` (printf-style, e.g. "$%.2f") formats the value server-side AND live.
  (let [sig-id   (or rxn id)
        in-item? (boolean (:item ctx))
        sig      (if in-item? (signals/item-$ ctx sig-id) (if rxn (signals/$ rxn) (signals/$ id)))
        current  (if in-item?
                   (get (:values ctx) sig-id)
                   (get (if rxn (:rxns ctx) (:values ctx)) sig-id))]
    [:span {"data-text" (fmt/fmt-text-expr fmt sig)}
     (str (if (nil? current) (or default "") (fmt/fmt-value fmt current)))]))
