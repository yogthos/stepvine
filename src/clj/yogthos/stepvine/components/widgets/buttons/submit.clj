(ns yogthos.stepvine.components.widgets.buttons.submit
  "Submit / revise buttons (§15.5). Submit finalizes a view (server guards
   sole-editor + validity, snapshots, and locks the document); revise reopens it.
   Visibility is driven by the document-level `$locked` signal the form seeds and
   the server broadcasts on submit/revise, so the pair swaps reactively."
  (:require
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/submit
  [ctx _component {:keys [view label]} _body]
  [:button.submit-btn
   {:type "button"
    "data-show"     "!$locked"                         ; hidden once finalized
    "data-on:click" (str "@post('/doc/" (:doc-id ctx) "/submit?view="
                         (name (or view :default)) "')")}
   (or label "Submit")])

(defmethod render-widget :stepvine.components/revise
  [ctx _component {:keys [view label]} _body]
  [:button.revise-btn
   {:type "button"
    "data-show"     "$locked"                          ; shown only when finalized
    "data-on:click" (str "@post('/doc/" (:doc-id ctx) "/revise?view="
                         (name (or view :default)) "')")}
   (or label "Revise")])
