(ns yogthos.stepvine.widgets.layout.box
  "Flexbox layout primitives (re-com's h-box / v-box). Containers that render
   their children in a row or column. :gap, :align and :justify map to modifier
   classes so all spacing/alignment stays in the theme — no inline styles. A
   :class string is appended for ad-hoc styling hooks."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defn- box-attrs [dir {:keys [gap align justify class]}]
  {:class (->> [(str "widget " dir)
                (when gap     (str "gap-" (name gap)))
                (when align   (str "align-" (name align)))
                (when justify (str "justify-" (name justify)))
                class]
               (remove nil?)
               (str/join " "))})

(defmethod render-widget :stepvine.components/h-box
  [ctx _component attrs body]
  (into [:div (box-attrs "h-box" attrs)] (render/render-children ctx body)))

(defmethod render-widget :stepvine.components/v-box
  [ctx _component attrs body]
  (into [:div (box-attrs "v-box" attrs)] (render/render-children ctx body)))
