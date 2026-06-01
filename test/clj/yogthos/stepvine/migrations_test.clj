(ns yogthos.stepvine.migrations-test
  "P3: form revisions + document migrations."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.migrations :as migrations]
   [yogthos.stepvine.session :as session]))

;; A form revised to v2: a migration renames :nickname -> :handle, and a new
;; derived field (:label) is computed from :handle.
(def form-v2
  '{:id :widget
    :version 2
    :migrations {1 (fn [db] (-> db (assoc :handle (:nickname db)) (dissoc :nickname)))}
    :data
    {:model  [[:handle {:id :handle :type :string}]
              [:label  {:id :label  :type :string}]]
     :events [{:id      :mk-label
               :inputs  [:handle]
               :outputs [:label]
               :handler (fn [{{:keys [handle]} :inputs}]
                          {:label (str "@" handle)})}]}})

(deftest migrate-applies-transforms-in-order
  (testing "a v1 db is brought up to the form's current version"
    (is (= {:handle "ada"}
           (migrations/migrate form-v2 1 {:nickname "ada"}))))
  (testing "no migration needed when already current"
    (is (= 2 (migrations/current-version form-v2)))
    (is (not (migrations/needs-migration? form-v2 2)))
    (is (migrations/needs-migration? form-v2 1))))

(deftest ensure-migrates-document-on-load
  (let [docs-store (atom {"d" {:id "d" :form-id :widget :form-version 1
                               :db {:nickname "ada"} :shared #{}}})
        forms      {:forms (atom {:widget form-v2})}
        mgr        (ig/init-key :session/manager {:documents docs-store :hub nil})
        res        {:forms forms :documents docs-store :session-manager mgr}]
    (docs/ensure! res "d")
    (testing "the migrated field is present and the derived field recomputed"
      (is (= "ada"  (session/value mgr "d" :handle)))
      (is (= "@ada" (session/value mgr "d" :label))))
    (testing "the migration is persisted and the version bumped"
      (let [doc (documents/get-document docs-store "d")]
        (is (= 2 (:form-version doc)))
        (is (= "ada" (get-in doc [:db :handle])))))
    (testing "a second load does not re-migrate (idempotent)"
      (reset! (:sessions mgr) {})                 ; force reload from persisted db
      (docs/ensure! res "d")
      (is (= "@ada" (session/value mgr "d" :label))))))

(deftest newly-created-documents-record-the-current-version
  (let [store (atom {})
        doc   (documents/create! store :widget nil 2)]
    (is (= 2 (:form-version doc)))
    (is (not (migrations/needs-migration? form-v2 (:form-version doc))))))
