(ns yogthos.stepvine.components.widgets.display.throbber
  "Loading spinner (re-com's throbber). Pure CSS structure; :size adds a modifier
   class (small | regular | large), all animation lives in the theme."
  (:require
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/throbber
  [_ctx _component {:keys [size label]} _body]
  [:div.widget.throbber-wrap
   (into [:ul {:class (str "throbber throbber-" (name (or size :regular)))}]
         (repeat 8 [:li]))
   (when label [:span.throbber-label label])])
