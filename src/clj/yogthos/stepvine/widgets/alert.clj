(ns yogthos.stepvine.widgets.alert
  "Alert widget — displays a styled message. Optionally conditional via :when signal."
  (:require
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/alert
  [ctx _component {:keys [class when label]} _body]
  [:div.widget.alert
   (cond-> {:class (str "alert " (or class "alert-info"))
            :role  "alert"}
     when (assoc "data-show" (str "$" (name when))))
   (or label "No message")])
