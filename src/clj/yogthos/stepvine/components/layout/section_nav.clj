(ns yogthos.stepvine.components.layout.section-nav
  "Jump-to-section navigation for long forms (parity stepvine-9by). `:c/section-nav`
   wraps a run of `:c/section`s and renders a sticky sidebar that lists each
   section title as an anchor link, scrolling to it on click. Non-section children
   (an intro paragraph, say) still render in document order but are left out of the
   table of contents.

     [:c/section-nav {:title \"On this page\"}
      [:c/section {:title \"Personal\"} …]
      [:c/section {:title \"Contact\"}  …]]"
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defn- section? [ctx node]
  (and (vector? node)
       (= :stepvine.components/section
          (render/resolve-component (:aliases ctx) (first node)))))

(defn- section-title [node]
  (when (map? (second node)) (:title (second node))))

(defmethod render-widget :stepvine.components/section-nav
  [ctx _component {:keys [title class]} body]
  (let [anchor   (fn [i] (str "sv-sec-" i))
        ;; absolute index keeps TOC links and section anchors in lock-step even
        ;; when non-section children are interleaved
        indexed  (map-indexed vector body)
        toc      (for [[i node] indexed :when (section? ctx node)]
                   [:li [:a {:href (str "#" (anchor i))}
                         (or (section-title node) (str "Section " (inc i)))]])
        rendered (for [[i node] indexed]
                   (if (section? ctx node)
                     [:div.sv-section-anchor {:id (anchor i)} (render/render-node ctx node)]
                     (render/render-node ctx node)))]
    [:div {:class (cond-> "sv-section-layout" class (str " " class))}
     [:nav.sv-section-nav
      (when title [:div.sv-section-nav-title title])
      (into [:ul] toc)]
     (into [:div.sv-section-body] rendered)]))
