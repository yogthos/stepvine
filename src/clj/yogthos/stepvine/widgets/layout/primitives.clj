(ns yogthos.stepvine.widgets.layout.primitives
  "Structural layout primitives ported from re-com: scroller, border, gap, line.
   Each emits only HTML structure with semantic + modifier classes — all spacing,
   sizing and rules live in the external theme. An optional :class is appended
   for ad-hoc styling hooks."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defn- classes [& cs]
  {:class (str/join " " (remove nil? cs))})

;; scroller — a panel that scrolls its overflowing children.
(defmethod render-widget :stepvine.components/scroller
  [ctx _component {:keys [class size]} body]
  (into [:div (classes "widget scroller"
                       (when size (str "scroller-" (name size)))
                       class)]
        (render/render-children ctx body)))

;; border — a bordered box wrapping its children.
(defmethod render-widget :stepvine.components/border
  [ctx _component {:keys [class rounded?]} body]
  (into [:div (classes "widget border-box"
                       (when rounded? "border-rounded")
                       class)]
        (render/render-children ctx body)))

;; gap — an empty spacer (size: sm | md | lg).
(defmethod render-widget :stepvine.components/gap
  [_ctx _component {:keys [size class]} _body]
  [:div (classes "widget gap" (str "gap-size-" (name (or size :md))) class)])

;; line — a divider rule (orientation: horizontal | vertical).
(defmethod render-widget :stepvine.components/line
  [_ctx _component {:keys [orientation class]} _body]
  [:div (assoc (classes "widget line"
                        (str "line-" (name (or orientation :horizontal)))
                        class)
               :role "separator")])
