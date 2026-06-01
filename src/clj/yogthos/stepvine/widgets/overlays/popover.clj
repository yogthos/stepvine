(ns yogthos.stepvine.widgets.overlays.popover
  "Anchored popover (re-com's popover). A trigger toggles a small floating panel
   whose visibility is local UI state in a client-side Datastar signal (data-show).
   :position (:below | :above | :left | :right) places the panel via a class. Body
   children are the popover content."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/popover
  [ctx _component {:keys [signal trigger-label position title]
                   :or {signal "popoverOpen" position :below}} body]
  (let [sig (str "$" signal)]
    [:div.popover-anchor
     [:button.popover-trigger
      {:type "button" "aria-haspopup" "true"
       "data-attr:aria-expanded" sig
       "data-on:click" (str sig " = !" sig)}
      (str (or trigger-label "Open"))]
     [:div.popover-body
      {:class (str "popover-" (name position))
       :role "dialog"
       "data-show" sig}
      (when title [:div.popover-title (str title)])
      (into [:div.popover-content] (render/render-children ctx body))]]))
