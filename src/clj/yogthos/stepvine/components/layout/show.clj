(ns yogthos.stepvine.components.layout.show
  "Conditional block — visible only when a reaction signal is truthy."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/show
  [ctx _component {:keys [when]} body]
  [:div {"data-show" (signals/$ when)}
   (render/render-children ctx body)])
