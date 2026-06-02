(ns yogthos.stepvine.migrations-test
  "P3 / §15.1: form versions + opt-in document rebase. Documents are pinned to the
   form version they were created against; `ensure!` loads that frozen version,
   and only the explicit `rebase!` moves a document onto a newer version."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.migrations :as migrations]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.versions :as versions]))

;; v1: a single :nickname field.
(def form-v1 '{:id :widget :version 1
               :data {:model [[:nickname {:id :nickname :type :string}]]}})

;; v2: a migration renames :nickname -> :handle, and a new derived field (:label)
;; is computed from :handle.
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

(defn- forms-store
  "A forms store whose archive holds every given version; the working copy is the
   last form per id."
  [& fs]
  (let [a (versions/archive)]
    (versions/publish-all! a fs)
    (forms/atom-store {:dir nil :forms (into {} (map (juxt :id identity) fs)) :versions a})))

(deftest migrate-applies-transforms-in-order
  (testing "a v1 db is brought up to the form's current version"
    (is (= {:handle "ada"}
           (migrations/migrate form-v2 1 {:nickname "ada"}))))
  (testing "version predicates"
    (is (= 2 (migrations/current-version form-v2)))
    (is (not (migrations/needs-migration? form-v2 2)))
    (is (migrations/needs-migration? form-v2 1))))

(deftest ensure-loads-the-pinned-version-without-migrating
  (let [docs-store (atom {"d" {:id "d" :form-id :widget :form-version 1
                               :db {:nickname "ada"} :shared #{}}})
        forms      (forms-store form-v1 form-v2)
        mgr        (ig/init-key :session/manager {:documents docs-store :hub nil})
        res        {:forms forms :documents docs-store :session-manager mgr}]
    (docs/ensure! res "d")
    (testing "the document loads its frozen v1 schema — no auto-migration"
      (is (= "ada" (session/value mgr "d" :nickname)))
      (is (nil? (session/value mgr "d" :handle)))      ; v2-only field absent
      (is (= 1 (:form-version (documents/get-document docs-store "d")))))))

(deftest rebase-moves-a-document-onto-the-latest-version
  (let [docs-store (atom {"d" {:id "d" :form-id :widget :form-version 1
                               :db {:nickname "ada"} :shared #{}}})
        forms      (forms-store form-v1 form-v2)
        mgr        (ig/init-key :session/manager {:documents docs-store :hub nil})
        res        {:forms forms :documents docs-store :session-manager mgr}]
    (docs/rebase! res "d")
    (testing "the migrated field is present and the derived field recomputed"
      (is (= "ada"  (session/value mgr "d" :handle)))
      (is (= "@ada" (session/value mgr "d" :label))))
    (testing "the rebase is persisted, the pin bumped, and the old db snapshotted"
      (let [doc (documents/get-document docs-store "d")]
        (is (= 2 (:form-version doc)))
        (is (= "ada" (get-in doc [:db :handle])))
        (is (= 1 (get-in doc [:meta :pre-rebase :version])))
        (is (= {:nickname "ada"} (get-in doc [:meta :pre-rebase :db])))))
    (testing "a second rebase is a no-op (already current)"
      (reset! (:sessions mgr) {})
      (docs/rebase! res "d")
      (is (= "@ada" (session/value mgr "d" :label))))))

(deftest newly-created-documents-pin-the-resolved-version
  (let [store (atom {})
        doc   (documents/create! store :widget {:form-version 2 :form-digest "abc"})]
    (is (= 2 (:form-version doc)))
    (is (= "abc" (:form-digest doc)))
    (is (= :in-progress (:status doc)))
    (is (not (migrations/needs-migration? form-v2 (:form-version doc))))))
