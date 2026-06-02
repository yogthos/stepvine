(ns yogthos.stepvine.form-store-test
  "The FormStore contract holds for both backends — the default map/disk store and
   the embedded SQLite app store — covering working form + CSS, the immutable
   version archive (sealed, CSS-free), and durability across a reopen."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.versions :as versions]))

(def form-v1 '{:id :widget :version 1 :css ".x{color:red}"
               :data {:model [[:a {:id :a :type :string}]]}})
(def form-v2 '{:id :widget :version 2 :css ".y{color:blue}"
               :data {:model [[:a {:id :a :type :string}] [:b {:id :b :type :string}]]}})

(defn- run-contract [store]
  (forms/save-form! store form-v1)
  (testing "working form is fetched with its CSS"
    (is (= :widget (:id (forms/get-form store :widget))))
    (is (= ".x{color:red}" (forms/css store :widget))))
  (testing "the version is archived without CSS (CSS is live, not pinned)"
    (is (= 1 (forms/latest-published store :widget)))
    (is (some? (forms/get-form-version store :widget 1)))
    (is (nil? (:css (forms/get-form-version store :widget 1))))
    (is (string? (forms/version-digest store :widget 1))))
  (testing "the live CSS href is cache-busting"
    (is (re-matches #"/app/widget/style\.css\?v=[0-9a-f]{12}" (forms/app-css-href store :widget))))
  (testing "publishing v2 seals v1; the working CSS follows the latest"
    (forms/save-form! store form-v2)
    (is (= 2 (forms/latest-published store :widget)))
    (is (= ".y{color:blue}" (forms/css store :widget)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (forms/save-form! store (assoc form-v1 :data {:model [[:z {:id :z}]]})))))
  (testing "listing"
    (is (some #{:widget} (forms/list-forms store)))))

(deftest map-backend-contract
  (run-contract (forms/atom-store {:dir nil :forms {} :versions (versions/archive)})))

(deftest sql-backend-contract
  (let [f "data/test-app-store.db"]
    (io/delete-file f true)
    (run-contract (ig/init-key :store/forms {:backend :sql :db-file f}))
    (io/delete-file f true)))

(deftest sql-backend-durable-across-reopen
  (let [f "data/test-app-durable.db"]
    (io/delete-file f true)
    (forms/save-form! (ig/init-key :store/forms {:backend :sql :db-file f}) form-v1)
    (testing "a fresh store on the same DB sees the persisted app + version"
      (let [s2 (ig/init-key :store/forms {:backend :sql :db-file f})]
        (is (= :widget (:id (forms/get-form s2 :widget))))
        (is (= ".x{color:red}" (forms/css s2 :widget)))
        (is (= 1 (forms/latest-published s2 :widget)))))
    (io/delete-file f true)))
