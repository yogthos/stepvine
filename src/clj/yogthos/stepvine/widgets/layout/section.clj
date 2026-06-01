(ns yogthos.stepvine.widgets.layout.section
  "Fieldset section — groups child widgets under an optional legend."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/section
  [ctx _component {:keys [title] :as attrs} body]
  [:fieldset (dissoc attrs :title :id)
   (when title [:legend title])
   (render/render-children ctx body)])
