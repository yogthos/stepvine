(ns yogthos.stepvine.components.widgets.tables.collection
  "Collection container — renders each item from the body template with
   item-scoped signals, plus add/remove controls. Collections nest: a
   `:c/collection` inside an item template reads its data from the parent item
   (the engine already stores nested data) and scopes its item signals/endpoints
   to the full path (§jj9)."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defn- collection-data
  "Resolve a collection's `{:order :field-opts :items}` — from the top-level
   collections map, or, when nested inside an item, from the parent item's values
   (`{idx {…}}`) and the parent field's `:schema`."
  [ctx coll-id parent-path]
  (if (seq parent-path)
    (let [items (get (:values ctx) coll-id)]
      {:order      (vec (keys items))
       :field-opts (signals/nested-collection-field-opts (get-in ctx [:field-opts coll-id]))
       :items      items})
    (get-in ctx [:collections coll-id])))

(defmethod render-widget :stepvine.components/collection
  [ctx _component {:keys [id]} body]
  (let [coll-id     id
        parent-path (vec (signals/item-path ctx))
        {:keys [order field-opts items]} (collection-data ctx coll-id parent-path)
        container   (str "coll-" (str/join "-" (map signals/signal-name (conj parent-path coll-id))))]
    (into [:div.collection {:id container}]
          (concat
           (for [idx order]
             (let [item-path (conj parent-path coll-id idx)
                   item-ctx  (assoc ctx
                                    :item   {:path item-path :coll coll-id :idx idx}
                                    :values (get items idx)
                                    :field-opts field-opts)]
               [:div.coll-item {:id (str "item-" (str/join "-" (map signals/signal-name item-path)))}
                (render/render-children item-ctx body)
                [:button.remove
                 {"data-on:click" (str "@post('" (endpoints/coll-item-url ctx item-path "remove") "')")}
                 "Remove"]]))
           [[:button.add
             {"data-on:click" (str "@post('" (endpoints/coll-item-url ctx (conj parent-path coll-id) "add") "')")}
             "+ Add"]]))))
