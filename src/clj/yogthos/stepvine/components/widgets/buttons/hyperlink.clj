(ns yogthos.stepvine.components.widgets.buttons.hyperlink
  "Anchor link (re-com's hyperlink / hyperlink-href). With :href it navigates;
   with :action it POSTs a server-side action (like :action button, but inline).
   Label is static (:label) or signal-bound (:rxn / :id)."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/hyperlink
  [ctx _component {:keys [href target label rxn id action disabled]} _body]
  (let [sig-id  (or rxn id)
        sig     (when sig-id (if rxn (render/$ sig-id) (render/item-$ ctx sig-id)))
        current (when sig-id
                  (if rxn (get (:rxns ctx) sig-id) (get-in ctx [:values sig-id])))
        action-url (when action (str "/doc/" (:doc-id ctx) "/action/" (name action)))]
    [:a.widget.hyperlink
     (cond-> {:href (or href "#")}
       target     (assoc :target target)
       disabled   (assoc :class "hyperlink disabled" "aria-disabled" "true")
       sig-id     (assoc "data-text" sig)
       (and action (not disabled))
       (assoc "data-on:click" (str "@post('" action-url "')")))
     (str (if (nil? current) (or label "") current))]))
