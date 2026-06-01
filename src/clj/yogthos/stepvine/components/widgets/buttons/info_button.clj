(ns yogthos.stepvine.components.widgets.buttons.info-button
  "Small circular ⓘ button that toggles an inline help popover (re-com's
   info-button). Visibility is local UI state in a client-side Datastar signal
   gated with data-show. :info is the help text (or supply body children)."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/info-button
  [ctx _component {:keys [signal info position]
                   :or {signal "infoOpen" position :below}} body]
  (let [sig (str "$" signal)]
    [:span.info-button-anchor
     [:button.info-button
      {:type "button" "aria-label" "More info" "aria-haspopup" "true"
       "data-attr:aria-expanded" sig
       "data-on:click" (str sig " = !" sig)}
      "i"]
     [:div.info-button-popover
      {:class (str "popover-" (name position)) :role "tooltip" "data-show" sig}
      (if (seq body)
        (into [:div.popover-content] (render/render-children ctx body))
        [:div.popover-content (str info)])]]))
