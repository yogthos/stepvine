(ns yogthos.stepvine.session-test
  "Phase 1/5: the reactive form-document engine works server-side, and the
   manager persists the latest db into the document record on every update."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.session :as session]))

(defn- fixture
  "A real :session/manager component wired to a document store pre-seeded with a
   record (so save-db! on update has something to persist into)."
  []
  (let [docs (atom {"d1" {:id "d1" :form-id :bmi :db {} :created-at 0}})
        mgr  (ig/init-key :session/manager {:documents docs :hub nil})]
    [docs mgr]))

(deftest form-loads-from-disk
  (let [form (forms/load-form "bmi")]
    (is (= :bmi (:id form)))
    (is (vector? (get-in form [:data :model])))
    (is (= [:calc-bmi] (map :id (get-in form [:data :events]))))))

(deftest bmi-document-recomputes-and-persists
  (let [form       (forms/load-form "bmi")
        [docs mgr] (fixture)
        id         (session/ensure-document! mgr "d1" form {})]

    (testing "no inputs yet -> bmi nil, category n/a"
      (is (nil?  (session/value mgr id :bmi)))
      (is (= "n/a" (session/value mgr id :bmi-category))))

    (testing "weight + height cascade into BMI and its reactions"
      (session/apply-change! mgr id [[:kg 100] [:m 2]])
      (is (= 25   (session/value mgr id :bmi)))
      (is (true?  (session/value mgr id :overweight?)))
      (is (= "overweight" (session/value mgr id :bmi-category))))

    (testing "changing only height recomputes BMI (dependency tracking)"
      (session/apply-change! mgr id [[:m 1]])
      (is (= 100 (session/value mgr id :bmi))))

    (testing "dropping below threshold flips the reaction back"
      (session/apply-change! mgr id [[:kg 20]])
      (is (= 20 (session/value mgr id :bmi)))
      (is (false? (session/value mgr id :overweight?))))

    (testing "the latest db is persisted into the document record"
      (is (= 20 (get-in (documents/get-document docs id) [:db :patient :bmi]))))))
