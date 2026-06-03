(ns yogthos.stepvine.components.widgets.buttons.hyperlink
  "Anchor link (re-com's hyperlink / hyperlink-href). With :href it navigates;
   with :action it POSTs a server-side action (like :action button, but inline).
   Label is static (:label) or signal-bound (:rxn / :id)."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/hyperlink
  [ctx _component {:keys [href doc-path target label rxn id action disabled]} _body]
  (let [sig-id  (or rxn id)
        sig     (when sig-id (if rxn (signals/$ sig-id) (signals/item-$ ctx sig-id)))
        current (when sig-id
                  (if rxn (get (:rxns ctx) sig-id) (get-in ctx [:values sig-id])))
        ;; :doc-path resolves to a path under this document (e.g. "report/0")
        href    (or href (when doc-path (str "/doc/" (:doc-id ctx) "/" doc-path)))
        action-url (when action (str "/doc/" (:doc-id ctx) "/action/" (name action)))]
    [:a.widget.hyperlink
     (cond-> {:href (or href "#")}
       target     (assoc :target target)
       disabled   (assoc :class "hyperlink disabled" "aria-disabled" "true")
       sig-id     (assoc "data-text" sig)
       (and action (not disabled))
       (assoc "data-on:click" (str "@post('" action-url "')")))
     (str (if (nil? current) (or label "") current))]))
