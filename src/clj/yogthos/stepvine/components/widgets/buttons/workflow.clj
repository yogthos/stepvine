(ns yogthos.stepvine.components.widgets.buttons.workflow
  "Workflow action button (§15.10). Triggers a state-machine action on the
   document; the server runs the mycelium FSM (guard → transition → effects).
   `:show-when` ties visibility to the document's `$state` signal (seeded by the
   form and broadcast on each transition), so each action appears only in the
   states it's legal from."
  (:require
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/workflow
  [ctx _component {:keys [action label show-when]} _body]
  [:button.wf-btn
   (cond-> {:type "button"
            "data-on:click" (str "@post('/doc/" (:doc-id ctx) "/wf/" (name action) "')")}
     show-when (assoc "data-show" (str "$state === '" (name show-when) "'")))
   (or label (name action))])
