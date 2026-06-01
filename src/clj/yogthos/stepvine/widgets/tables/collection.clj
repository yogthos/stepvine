(ns yogthos.stepvine.widgets.tables.collection
  "Collection container — renders each item from the body template with
   item-scoped signals, plus add/remove controls."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/collection
  [ctx _component {:keys [id]} body]
  (let [coll-id id
        {:keys [order field-opts items]} (get-in ctx [:collections coll-id])
        doc-id   (:doc-id ctx)
        coll-nm  (name coll-id)]
    [:div.collection {:id (str "coll-" coll-nm)}
     (for [idx order]
       (let [item-ctx (assoc ctx
                             :item {:coll coll-id :idx idx}
                             :values (get items idx)
                             :field-opts field-opts)]
         [:div.coll-item {:id (str "item-" coll-nm "-" idx)}
          (render/render-children item-ctx body)
          [:button.remove
           {"data-on:click"
            (str "@post('/doc/" doc-id "/coll/" coll-nm "/" idx "/remove')")}
           "Remove"]]))
     [:button.add
      {"data-on:click" (str "@post('/doc/" doc-id "/coll/" coll-nm "/add')")}
      "+ Add"]]))
