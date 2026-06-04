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

(deftest locking-cascades-to-related-fields
  ;; Editing a field that feeds (or is fed by) a derived field must lock the whole
  ;; related set so a second user can't race the inputs of a shared computation.
  ;; BMI: :kg and :m both feed :bmi via the :calc-bmi event — locking any of the
  ;; three co-locks all three (transitively, via Domino's events graph).
  (testing "top-level: locking :kg co-locks its event siblings :m and :bmi"
    (let [form (forms/load-form "bmi")
          h    (atom {})
          mgr  (ig/init-key :session/manager {:documents (atom {}) :hub h})
          peer (ds-test/->sse-recorder)]
      (session/ensure-document! mgr "bmi" form {})
      (hub/register! h "bmi" "a" peer "userA")
      (is (= :ok (session/lock-field! mgr h "bmi" "userA" :kg)))
      (let [e (last-event peer)]
        (is (str/includes? e "\"kg\":\"userA\""))
        (is (str/includes? e "\"m\":\"userA\"")   "height (co-input) is co-locked")
        (is (str/includes? e "\"bmi\":\"userA\"") "the derived field is co-locked"))
      (testing "a second user cannot save a co-locked sibling"
        (is (= :rejected (session/apply-field-as! mgr "bmi" "userB" :m 1.8)))
        (is (nil? (session/value mgr "bmi" :m))))
      (testing "the lock holder can edit any field in the related set"
        (is (= :ok (session/apply-field-as! mgr "bmi" "userA" :m 1.8)))
        (is (= 1.8 (session/value mgr "bmi" :m))))))

  ;; Collections: the cascade is scoped to a single item — editing one member's
  ;; first name must not lock another member's fields.
  (testing "collection: the per-item cascade does not cross items"
    (let [form (forms/load-form "roster")
          h    (atom {})
          mgr  (ig/init-key :session/manager {:documents (atom {}) :hub h})
          db   {:members {"a1" {:first "Grace" :last "Hopper"}
                          "a2" {:first "Ada"   :last "Lovelace"}}}]
      (session/ensure-document! mgr "roster" form db)
      (is (= :ok (session/lock-item-field! mgr h "roster" "userA" :members "a1" :first)))
      (testing "the same item's derived/sibling fields are co-locked for others"
        (is (= :rejected (session/apply-item-field-as! mgr "roster" "userB" :members "a1" :last "X")))
        (is (= :rejected (session/apply-item-field-as! mgr "roster" "userB" :members "a1" :full "X"))))
      (testing "a different item is unaffected"
        (is (= :ok (session/apply-item-field-as! mgr "roster" "userB" :members "a2" :first "Augusta")))
        (is (= "Augusta" (session/value mgr "roster" [:members "a2" :first])))))))
