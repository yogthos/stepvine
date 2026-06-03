(ns yogthos.stepvine.form-store-test
  "The FormStore contract holds for both backends — the default map/disk store and
   the embedded SQLite app store — covering working form + CSS, the immutable
   version archive (sealed, CSS-free), and durability across a reopen."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [yogthos.stepvine.forms-compile :as forms-compile]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.versions :as versions]))

(def form-v1 '{:id :widget :version 1 :css ".x{color:red}"
               :data {:model [[:a {:id :a :type :string}]]}})
(def form-v2 '{:id :widget :version 2 :css ".y{color:blue}"
               :data {:model [[:a {:id :a :type :string}] [:b {:id :b :type :string}]]}})

(defn- run-contract [store]
  (forms/save-form! store form-v1)
  (testing "working form is fetched with its CSS"
    (is (= :widget (:id (forms-compile/get-form store :widget))))
    (is (= ".x{color:red}" (forms/css store :widget))))
  (testing "the version is archived without CSS (CSS is live, not pinned)"
    (is (= 1 (forms/latest-published store :widget)))
    (is (some? (forms-compile/get-form-version store :widget 1)))
    (is (nil? (:css (forms-compile/get-form-version store :widget 1))))
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
        (is (= :widget (:id (forms-compile/get-form s2 :widget))))
        (is (= ".x{color:red}" (forms/css s2 :widget)))
        (is (= 1 (forms/latest-published s2 :widget)))))
    (io/delete-file f true)))

;; --- Drafts authoring flow (stepvine-lqj) ----------------------------------

(def draft-base '{:id :gizmo :version 1 :data {:model [[:a {:id :a :type :string}]]}})
(def draft-v2   '{:id :gizmo :version 2 :css ".g{color:green}"
                  :data {:model [[:a {:id :a :type :string}] [:b {:id :b :type :string}]]}})

(defn- run-draft-contract [store]
  (forms/save-form! store draft-base)                 ; publish v1 (the live form)
  (testing "no draft initially — the editor falls back to the working form"
    (is (not (forms/has-draft? store :gizmo)))
    (is (nil? (forms/draft store :gizmo)))
    (is (= draft-base (forms/for-editing store :gizmo))))
  (testing "save-draft! stores WIP — it does NOT publish or change the working form"
    (forms/save-draft! store draft-v2)
    (is (forms/has-draft? store :gizmo))
    (is (= 2 (:version (forms/draft store :gizmo))))
    (is (= ".g{color:green}" (:css (forms/draft store :gizmo))))
    (is (= 1 (forms/latest-published store :gizmo)))         ; NOT published
    (is (= 1 (:version (forms/raw-form store :gizmo))))      ; working form unchanged
    (is (nil? (forms/version-digest store :gizmo 2))))       ; v2 not archived
  (testing "the editor loads the draft when one exists"
    (is (= 2 (:version (forms/for-editing store :gizmo)))))
  (testing "publish-draft! promotes the draft -> working form + new version, clears the draft"
    (is (= 2 (:version (forms/publish-draft! store :gizmo))))
    (is (not (forms/has-draft? store :gizmo)))
    (is (= 2 (forms/latest-published store :gizmo)))
    (is (= 2 (:version (forms/raw-form store :gizmo))))      ; working form is now v2
    (is (some? (forms/version-digest store :gizmo 2))))
  (testing "discard-draft! drops a draft, leaving the working form untouched"
    (forms/save-draft! store (assoc draft-v2 :version 3))
    (is (forms/has-draft? store :gizmo))
    (forms/discard-draft! store :gizmo)
    (is (not (forms/has-draft? store :gizmo)))
    (is (= 2 (:version (forms/raw-form store :gizmo))))      ; still v2
    (is (= 2 (forms/latest-published store :gizmo)))))

(deftest map-backend-draft-contract
  (run-draft-contract (forms/atom-store {:dir nil :forms {} :versions (versions/archive)})))

(deftest sql-backend-draft-contract
  (let [f "data/test-draft-store.db"]
    (io/delete-file f true)
    (run-draft-contract (ig/init-key :store/forms {:backend :sql :db-file f}))
    (io/delete-file f true)))
