(ns yogthos.stepvine.documents-test
  "Phase 5: document instances are created, persist their db, reload into a fresh
   session (restart recovery), and produce templated exports."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.exports :as exports]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.editor.impl :as impl]))

(defn- setup []
  (let [docs-store (atom {})
        mgr        (ig/init-key :session/manager {:documents docs-store :hub nil})
        forms-store (forms/atom-store {:dir "forms" :forms {:bmi (forms/load-form "bmi")}})]
    {:documents docs-store :session-manager mgr :forms forms-store}))

(deftest create-edit-persist-reload
  (let [{:keys [documents session-manager] :as res} (setup)
        doc (documents/create! documents :bmi)
        id  (:id doc)]
    (testing "a created document is a distinct instance of its form"
      (is (= :bmi (:form-id doc)))
      (is (= [doc] (documents/list-documents documents))))

    (docs/ensure! res id)
    (session/apply-change! session-manager id [[:kg 100] [:m 2]])

    (testing "edits are persisted into the document record's db"
      (is (= 25 (get-in (documents/get-document documents id) [:db :patient :bmi]))))

    (testing "after a 'restart' (live session dropped) the doc reloads from its db"
      (reset! (:sessions session-manager) {})        ; simulate process restart
      (is (nil? (session/current-maybe session-manager id)))
      (docs/ensure! res id)                          ; recreate session from persisted db
      (is (= 100 (session/value session-manager id :kg)))
      (is (= 25  (session/value session-manager id :bmi))))))

(deftest templated-export-substitutes-values
  (let [form (forms/load-form "bmi")
        sess (impl/apply-changes (impl/create-session form {}) [[:kg 100] [:m 2]])
        result (exports/render-export sess :summary)]
    (testing "field + reaction placeholders are substituted"
      (is (= 100 (:weightKg result)))
      (is (= 2   (:heightM result)))
      (is (= 25  (:bmi result)))
      (is (= "overweight" (:category result))))
    (testing ":fn/uuid and :fn/date are generated"
      (is (string? (:id result)))
      (is (string? (:generated result)))
      (is (= "Observation" (:resourceType result))))
    (testing "unknown export id yields nil"
      (is (nil? (exports/render-export sess :nope))))))

;; --- Content search, auth-scoped (§j00) -----------------------------------

(deftest search-is-scoped-to-accessible-documents
  (let [store (atom {})
        a (documents/create! store :ticket {:created-by "alice"})
        b (documents/create! store :ticket {:created-by "bob"})]
    (documents/save-db! store (:id a) {:title "Broken printer" :detail "the laser jet is jammed"})
    (documents/save-db! store (:id b) {:title "VPN outage" :detail "cannot connect"})
    (testing "a user finds their own matching document"
      (is (= [(:id a)] (map :id (documents/search-accessible store "alice" "printer")))))
    (testing "search NEVER returns another user's document (auth scope)"
      (is (empty? (documents/search-accessible store "alice" "vpn")))     ; bob's doc
      (is (empty? (documents/search-accessible store "bob" "printer"))))  ; alice's doc
    (testing "a shared document becomes searchable by the sharee"
      (documents/share! store (:id b) "alice")
      (is (= [(:id b)] (map :id (documents/search-accessible store "alice" "vpn")))))
    (testing "match is case-insensitive over field values and the form id"
      (is (seq (documents/search-accessible store "alice" "BROKEN")))
      (is (seq (documents/search-accessible store "alice" "ticket"))))   ; matches :form-id
    (testing "a blank query returns every accessible document (own + shared)"
      (is (= 2 (count (documents/search-accessible store "alice" "")))))
    (testing "nested / collection field values are searchable too"
      (documents/save-db! store (:id a) {:title "Broken printer"
                                         :lines {"l1" {:item "toner cartridge"}}})
      (is (= [(:id a)] (map :id (documents/search-accessible store "alice" "toner")))))))

;; --- Optimistic concurrency (:rev) -----------------------------------------

(deftest rev-current-detects-stale-revisions
  (let [store (atom {})
        d (documents/create! store :ticket {:created-by "u1"})
        id (:id d)]
    (testing "a fresh document is at rev 0"
      (is (= 0 (documents/current-rev store id))))
    (testing "rev 0 is current; an out-of-date rev is not"
      (is (documents/rev-current? store id 0))
      (is (not (documents/rev-current? store id -1))))
    (testing "a write bumps the rev, so the old rev goes stale"
      (documents/save-db! store id {:title "x"})
      (is (= 1 (documents/current-rev store id)))
      (is (not (documents/rev-current? store id 0)))   ; client that saw rev 0 is now stale
      (is (documents/rev-current? store id 1)))
    (testing "a nil rev (no token) is always treated as current"
      (is (documents/rev-current? store id nil)))))
