(ns yogthos.stepvine.widgets.buttons.action
  "Action buttons — trigger a server-side action (e.g. an export), or the form
   builder's build step."
  (:require
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/action
  [ctx _component {:keys [action label]} _body]
  ;; Triggers a server-side action (e.g. a templated export) for this document.
  [:button.action
   {"data-on:click" (str "@post('/doc/" (:doc-id ctx) "/action/" (name action) "')")}
   (or label (str "Run " (name action)))])

(defmethod render-widget :stepvine.components/build-button
  [ctx _component {:keys [label]} _body]
  ;; Form-builder: generate + save a real form from this builder document.
  [:button.build
   {"data-on:click" (str "@post('/doc/" (:doc-id ctx) "/build')")}
   (or label "Build form")])
