(ns yogthos.stepvine.workflow-fsm-test
  "Phase 10: the document workflow expressed as a mycelium FSM — running the
   compiled state machine end-to-end (load → guard → branch → effects → commit /
   reject) and asserting the persisted transition, locking, snapshot and audit."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [mycelium.core :as myc]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.versions :as versions]
   [yogthos.stepvine.workflows.workflow :as wf-fsm]))

(def ticket-form
  '{:id :ticket :version 1
    :data {:model [[:title {:id :title :type :string :validation [:required]}]]}
    :workflow {:initial :open
               :states  {:open   {:on {:submit :review}}
                         :review {:on {:approve :closed :reject :open}}
                         :closed {:terminal? true :locked? true}}
               :actions {:submit  {:guard :valid?
                                   :steps [{:do :notify :message "Submitted for review"}]}
                         :approve {:steps [{:do :snapshot}]}}}})

(defn- setup []
  (let [archive (versions/archive)
        _       (versions/publish! archive ticket-form)
        forms   (forms/atom-store {:dir nil :forms {:ticket ticket-form} :versions archive})
        docs    (atom {})
        h       (atom {})
        mgr     (ig/init-key :session/manager {:documents docs :hub h})
        aud     (audit/store)
        doc     (documents/create! docs :ticket {:created-by "u1" :form-version 1})
        res     {:forms forms :documents docs :session-manager mgr :hub h :audit aud
                 :users (atom {"u1" {:id "u1" :roles #{:reviewer}}})}]
    {:res res :id (:id doc) :mgr mgr :docs docs :audit aud}))

(defn- run
  ([res id action uid] (run res id action uid nil))
  ([res id action uid rev]
   (myc/run-compiled wf-fsm/run-action res
                     {:http-request (cond-> {:path-params {:id id :action (name action)}
                                             :session {:user-id uid}}
                                      rev (assoc :request-method :post
                                                 :body (jsonista.core/write-value-as-string {:rev rev})))})))

(deftest legal-transition-runs-and-persists
  (let [{:keys [res id mgr docs audit]} (setup)]
    (docs/ensure! res id)
    (session/apply-change! mgr id [[:title "Printer is down"]])   ; make :valid? true
    (let [out (run res id :submit "u1")]
      (testing "the FSM commits (204)"
        (is (= 204 (:status out))))
      (testing "the document transitioned to :review"
        (is (= :review (documents/workflow-state (documents/get-document docs id) :open))))
      (testing "the transition + action were audited"
        (let [actions (set (map :action (audit/for-document audit id)))]
          (is (contains? actions :state/transition))
          (is (contains? actions :wf/action)))))))

(deftest guards-reject-without-side-effects
  (let [{:keys [res id docs]} (setup)]
    (docs/ensure! res id)
    (testing "submit with an invalid (blank-title) form is rejected"
      (let [out (run res id :submit "u1")]
        (is (= 409 (:status out)))
        (is (= "invalid" (:body out)))
        (is (= :open (documents/workflow-state (documents/get-document docs id) :open)))))
    (testing "an illegal transition for the current state is rejected"
      (let [out (run res id :approve "u1")]                      ; :approve not legal from :open
        (is (= 409 (:status out)))
        (is (= "illegal-transition" (:body out)))))))

(deftest stale-revision-is-rejected
  (let [{:keys [res id mgr docs]} (setup)]
    (docs/ensure! res id)
    (session/apply-change! mgr id [[:title "Printer is down"]])   ; valid + bumps :rev
    (let [cur (documents/current-rev docs id)]
      (testing "an action carrying the current rev proceeds"
        (is (= 204 (:status (run res id :submit "u1" cur))))
        (is (= :review (documents/workflow-state (documents/get-document docs id) :open))))
      (testing "an action carrying a stale (older) rev is rejected 409, no transition"
        (let [out (run res id :approve "u1" (dec cur))]           ; pretend client is behind
          (is (= 409 (:status out)))
          (is (= "stale" (:body out)))
          (is (= :review (documents/workflow-state (documents/get-document docs id) :open))))))
    (testing "an action with no rev token still works (legacy / untracked client)"
      (is (= 204 (:status (run res id :approve "u1")))))))

(deftest terminal-transition-locks-and-snapshots
  (let [{:keys [res id mgr docs]} (setup)]
    (docs/ensure! res id)
    (session/apply-change! mgr id [[:title "x"]])
    (run res id :submit "u1")                                    ; open -> review
    (run res id :approve "u1")                                   ; review -> closed (locked, snapshot)
    (let [doc (documents/get-document docs id)]
      (testing "reached the terminal locked state"
        (is (= :closed (documents/workflow-state doc :open)))
        (is (documents/locked? doc)))                            ; status mirrored to :submitted
      (testing "the :snapshot step recorded a report"
        (is (= 1 (count (get-in doc [:meta :reports]))))
        (is (= {:title "x"} (-> doc (get-in [:meta :reports]) first :snapshot)))))))
