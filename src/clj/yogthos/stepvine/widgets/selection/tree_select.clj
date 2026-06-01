(ns yogthos.stepvine.widgets.selection.tree-select
  "Hierarchical multi-check tree (re-com's tree-select). Options form a tree of
   {:label :value :children [...]}; each leaf is a checkbox bound to an :array
   field signal (reactive over the signal like selection-list). Groups use native
   <details>/<summary> for expand/collapse — no extra signal needed."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]
   [yogthos.stepvine.widgets.selection.array-signal :as arr]))

(defn- leaf [ctx sig sel url read-only {:keys [label value]}]
  [:li.tree-leaf
   [:label
    [:input
     (cond-> {:type "checkbox"
              :checked (contains? sel value)
              "data-attr:checked" (arr/includes-expr sig value)}
       read-only       (assoc :disabled true)
       (not read-only) (assoc "data-on:change" (arr/toggle-expr sig value url)))]
    [:span (str label)]]])

(defn- branch [render-node* {:keys [label children open?]}]
  [:li.tree-branch
   (into [:details (when open? {:open true})
          [:summary (str label)]]
         [(into [:ul.tree-children] (map render-node* children))])])

(defmethod render-widget :stepvine.components/tree-select
  [ctx _component {:keys [id label nodes read-only]} _body]
  (let [sig     (render/item-signal-name ctx id)
        current (get-in ctx [:values id])
        sel     (set (when (sequential? current) current))
        url     (render/field-post-url ctx id)
        render-node* (fn render-node* [node]
                       (if (:children node)
                         (branch render-node* node)
                         (leaf ctx sig sel url read-only node)))]
    [:div.widget.tree-select.field
     (when label [:label label])
     (into [:ul.tree-root] (map render-node* (or nodes [])))]))
