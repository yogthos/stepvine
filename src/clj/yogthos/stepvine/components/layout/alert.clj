(ns yogthos.stepvine.components.layout.alert
  "Alert widgets — a single styled message (alert) and a stack of them
   (alert-list, re-com's alert-list). Optionally conditional via :when signal."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/alert
  [ctx _component {:keys [class when label]} _body]
  [:div.widget.alert
   (cond-> {:class (str "alert " (or class "alert-info"))
            :role  "alert"}
     when (assoc "data-show" (str "$" (name when))))
   (or label "No message")])

(defmethod render-widget :stepvine.components/alert-list
  [ctx _component {:keys [label alerts]} body]
  ;; Render from an :alerts data vector ({:class :heading :body}) and/or arbitrary
  ;; body children (e.g. nested :alert widgets).
  [:div.widget.alert-list
   (when label [:label label])
   (into [:div.alert-list-items]
         (concat
          (for [{:keys [class heading body]} alerts]
            [:div {:class (str "alert " (or class "alert-info")) :role "alert"}
             (when heading [:strong.alert-heading (str heading)])
             [:span (str body)]])
          (render/render-children ctx body)))])
