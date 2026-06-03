(ns yogthos.stepvine.resilience-test
  "Multi-step action resilience (stepvine-8gj): a workflow action's side-effect
   steps run as a saga — idempotent (at-most-once), retried, compensated, and the
   state transition commits only after every effect succeeds."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.directives :as directives]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.effects :as effects]
   [yogthos.stepvine.http :as http]))

;; --- test-only effect kinds (unique :kind keys, no collision) --------------
(defmethod effects/perform! :test/ok [_ _ _ intent]
  (swap! (:sink intent) conj (:tag intent)) :ok)
(defmethod effects/perform! :test/fail [_ _ _ _] (throw (ex-info "boom" {})))
(defmethod effects/perform! :test/flaky [_ _ _ intent]
  (let [n (swap! (:calls intent) inc)]
    (when (< n (:succeed-on intent)) (throw (ex-info "transient" {})))
    (swap! (:sink intent) conj :flaky-ok) :ok))

(defn- setup []
  (let [store (atom {})
        doc   (documents/create! store :x {:created-by "u"})]
    {:resources {:documents store} :doc-id (:id doc)}))

(deftest idempotency-skips-completed-steps
  (let [{:keys [resources doc-id]} (setup)
        sink  (atom [])
        steps [{:key "k/0" :intent {:kind :test/ok :tag :a :sink sink}}
               {:key "k/1" :intent {:kind :test/ok :tag :b :sink sink}}]]
    (is (= :ok (:status (effects/run-saga! resources doc-id nil steps))))
    (is (= [:a :b] @sink))
    (testing "re-running the same transition skips the steps already logged :ok"
      (is (= :ok (:status (effects/run-saga! resources doc-id nil steps))))
      (is (= [:a :b] @sink)))))

(deftest retry-recovers-transient-failures
  (let [{:keys [resources doc-id]} (setup)
        sink (atom []) calls (atom 0)]
    (let [r (effects/run-saga! resources doc-id nil
                               [{:key "r/0" :intent {:kind :test/flaky :succeed-on 3 :retry 2
                                                     :calls calls :sink sink}}])]
      (testing "a step retried within its budget eventually succeeds"
        (is (= :ok (:status r)))
        (is (= 3 @calls))            ; failed on 1 and 2, succeeded on the 3rd attempt
        (is (= [:flaky-ok] @sink))))))

(deftest exhausted-retries-fail-and-compensate
  (let [{:keys [resources doc-id]} (setup)
        sink (atom []) comped (atom [])
        steps [{:key "c/0" :intent {:kind :test/ok :tag :a :sink sink}
                :compensate {:kind :test/ok :tag :undo-a :sink comped}}
               {:key "c/1" :intent {:kind :test/fail}}]]
    (let [r (effects/run-saga! resources doc-id nil steps)]
      (testing "the failing step is reported; completed steps are compensated in reverse"
        (is (= :failed (:status r)))
        (is (= "c/1" (:failed-step r)))
        (is (= [:a] @sink))          ; step A ran
        (is (= [:undo-a] @comped)))  ; A's compensation ran after B failed
      (testing "the durable effect log records each outcome"
        (let [by-key (into {} (map (juxt :key :status)) (effects/effect-log (:documents resources) doc-id))]
          (is (= :ok     (by-key "c/0")))
          (is (= :failed (by-key "c/1"))))))))

;; --- transition atomicity through directives/apply! ------------------------

(def ^:private boom-client
  (reify http/HttpClientP
    (-send! [_ _] (throw (ex-info "service down" {})))
    (-log   [_]   nil)))

(def ^:private wf-form
  '{:id :x :workflow {:states {:review {:on {:approve :closed}}
                               :closed {:terminal? true :locked? true}}}})

(deftest a-failed-external-step-blocks-the-transition
  (let [store (atom {})
        doc   (documents/create! store :x {:created-by "u"})
        id    (:id doc)
        resources {:documents store :session-manager nil :hub (atom {})
                   :audit (audit/store) :http-client boom-client}
        ;; approve: an :http step that fails, then the transition to :closed
        directives [[:http {:url "http://svc.test/close" :host-allow ["svc.test"]}]
                    [:set-state :closed]]
        r (directives/apply! resources wf-form id "u" :approve :review directives)]
    (testing "the saga fails"
      (is (= :failed (:status r))))
    (testing "the document did NOT transition (set-state was refused)"
      (is (not= :closed (documents/workflow-state (documents/get-document store id) :review))))
    (testing "a retry would skip nothing yet (the http step never logged :ok)"
      (is (not (effects/logged-ok? store id "review/approve/0"))))))
