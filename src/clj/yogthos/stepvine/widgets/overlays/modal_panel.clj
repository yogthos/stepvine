(ns yogthos.stepvine.widgets.overlays.modal-panel
  "Modal dialog (re-com's modal-panel). Visibility is local UI state held in a
   client-side Datastar signal (named via :signal) and gated with data-show — no
   server round-trip, matching re-com where modal open/closed is view state. An
   optional trigger button opens it; a backdrop click or close button shuts it.
   Body children are the modal content."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/modal-panel
  [ctx _component {:keys [signal title trigger-label closeable?]
                   :or {signal "modalOpen" closeable? true}} body]
  (let [sig   (str "$" signal)
        close (str sig " = false")]
    [:div.modal-root
     (when trigger-label
       [:button.modal-trigger {:type "button" "data-on:click" (str sig " = true")}
        trigger-label])
     [:div.modal-overlay
      {"data-show" sig
       "data-on:click" close}                      ; backdrop click closes
      [:div.modal-panel
       {:role "dialog" "aria-modal" "true"
        "data-on:click" "evt.stopPropagation()"}   ; clicks inside don't close
       (when (or title closeable?)
         [:div.modal-header
          [:h3.modal-title (str title)]
          (when closeable?
            [:button.modal-close {:type "button" :aria-label "Close"
                                  "data-on:click" close} "×"])])
       (into [:div.modal-body] (render/render-children ctx body))]]]))
