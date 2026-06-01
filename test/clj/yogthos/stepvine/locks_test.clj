(ns yogthos.stepvine.locks-test
  "Phase 3b: field locking is server-authoritative (reusing yogthos.stepvine.editor's lock
   manager) and its state is broadcast so peers can disable held fields."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.session :as session]
   [starfederation.datastar.clojure.adapter.test :as ds-test]))

(defn- last-event [recorder] (last @(:!rec recorder)))

(deftest locking-broadcasts-and-enforces
  (let [form (forms/load-form "bmi")
        docs (atom {})
        h    (atom {})
        mgr  (ig/init-key :session/manager {:documents docs :hub h})
        peer (ds-test/->sse-recorder)]
    (session/ensure-document! mgr "bmi" form {})
    (hub/register! h "bmi" "a" peer "userA")

    (testing "locking a field broadcasts the locks signal naming the holder"
      (is (= :ok (session/lock-field! mgr h "bmi" "userA" :kg)))
      (let [e (last-event peer)]
        (is (str/includes? e "datastar-patch-signals"))
        (is (str/includes? e "locks"))
        (is (str/includes? e "\"kg\":\"userA\""))))

    (testing "another user cannot save a field locked by someone else"
      (is (= :rejected (session/apply-field-as! mgr "bmi" "userB" :kg 99)))
      (is (nil? (session/value mgr "bmi" :kg))))

    (testing "the lock holder can save it"
      (is (= :ok (session/apply-field-as! mgr "bmi" "userA" :kg 80)))
      (is (= 80 (session/value mgr "bmi" :kg))))

    (testing "unlocking clears the lock signal (null under merge-patch)"
      (session/unlock-field! mgr h "bmi" "userA" :kg)
      (is (str/includes? (last-event peer) "\"kg\":null")))

    (testing "after release, a different user can now save"
      (is (= :ok (session/apply-field-as! mgr "bmi" "userB" :kg 70)))
      (is (= 70 (session/value mgr "bmi" :kg))))))
