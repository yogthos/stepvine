(ns yogthos.stepvine.editor.data
  (:require [domino.core :as d]
            [clojure.set :refer [union rename-keys]]))


;; ==============================================================================
;; This namespace adds indirection around domino so that the API can be changed.

(defn transact-ctx
  "Given a document session, this will perform the requested changes.
  It will trigger any neccessary rules and will return the new ctx, containing a complete list of changes in the order performed, the new DB state, and a list of effects that should be triggered(?).
  This change list should yield the current state without the need for any engine."
  [session changes]
  (update session
          ::ctx
          d/transact changes))

(defn get-related-fn [ctx]
  "Generates a function which computes related paths based on the passed context."
  (comp (partial apply union)
        (juxt (partial d/get-downstream ctx) (partial d/get-upstream ctx))))

(defn get-parents-fn [ctx]
  (partial d/get-parents ctx))

(defn initialize-ctx
  "Given a form and an initial DB state, this generates all of the context neccessary for operating on the data in a transactional manner."
  [form initial-db]
  (d/initialize (:data form) initial-db))

(defn get-db
  "Gets the DB from the context"
  [ctx]
  (::d/db ctx nil))

(defn get-tx-report
  [ctx]
  (::d/transaction-report ctx nil))

(defn get-value
  [ctx id]
  (d/select ctx id)
  #_
  ;; TODO: use domino `select` fn once implemented
  (when-some [path (get (::d/id->path ctx) id)]
    (some-> ctx
            get-db
            (get-in path))))

;; TODO: THIS IS A MAP.
;;       SYNC IT AS A MAP ON THE COMPILED FORM
;;       STORE THE COMPILED VERSION OF IT IN THE BUILD
(defn get-field-opts-fn [ctx]
  (fn [id]
    (rename-keys
     (get (::d/id->opts ctx) id)
     {::d/path :path})))

(defn get-field-opts-map [ctx]
  (into {}
        (map (fn [[k v]]
               [k (rename-keys v {::d/path :path})]))
        (::d/id->opts ctx)))

(defn get-fields [session]
  (keys (::d/id->opts (::ctx session))))
