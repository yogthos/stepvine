(ns yogthos.stepvine.document-store-test
  "Phase 11 (§15.13): the DocStore contract holds for both backends — the default
   atom and the embedded SQLite query store — including indexed queries and
   on-disk durability across a reopen."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [yogthos.stepvine.documents :as documents]))

(defn- run-contract [store]
  (let [doc (documents/create! store :bmi {:created-by "u1" :form-version 2 :form-digest "d"})
        id  (:id doc)]
    (testing "create + fetch carries the pinned fields and status"
      (let [d (documents/get-document store id)]
        (is (= :in-progress (:status d)))
        (is (= 2 (:form-version d)))
        (is (= "d" (:form-digest d)))))
    (testing "save-db! persists the db and bumps :rev"
      (documents/save-db! store id {:kg 100})
      (let [d (documents/get-document store id)]
        (is (= {:kg 100} (:db d)))
        (is (= 1 (:rev d)))))
    (testing "submit! moves to :submitted"
      (documents/submit! store id :default "u1" {:snap 1})
      (is (= :submitted (:status (documents/get-document store id)))))
    (testing "indexed query by owner + status"
      (is (= #{id} (set (map :id (documents/find-by store {:owner "u1" :status :submitted})))))
      (is (empty? (documents/find-by store {:status :in-progress}))))
    (testing "access control"
      (is (= [id] (map :id (documents/accessible-by store "u1"))))
      (is (empty? (documents/accessible-by store "someone-else"))))
    (testing "delete"
      (documents/delete! store id)
      (is (nil? (documents/get-document store id))))))

(deftest atom-backend-contract
  (run-contract (atom {})))

(deftest sql-backend-contract
  (let [f "data/test-doc-store.db"]
    (io/delete-file f true)
    (run-contract (ig/init-key :store/documents {:backend :sql :db-file f}))
    (io/delete-file f true)))

(deftest sql-backend-is-durable-across-reopen
  (let [f "data/test-doc-durable.db"]
    (io/delete-file f true)
    (let [s1 (ig/init-key :store/documents {:backend :sql :db-file f})
          id (:id (documents/create! s1 :bmi {:created-by "u1"}))]
      (documents/save-db! s1 id {:kg 80}))
    (testing "a fresh store on the same file sees the persisted document"
      (let [s2 (ig/init-key :store/documents {:backend :sql :db-file f})
            d  (first (documents/list-documents s2))]
        (is (= {:kg 80} (:db d)))
        (is (= "u1" (:owner d)))))
    (io/delete-file f true)))
