(ns yogthos.stepvine.validation-test
  "Phase 9 (§15.8): a declarative validation vocabulary compiled to Domino error
   reactions + a document :valid? reaction, exercised through a real session."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.validation :as validation]
   [yogthos.stepvine.editor.impl :as impl]))

(def form
  '{:id :reg
    :data
    {:model
     [[:name  {:id :name  :type :string :validation [:required [:max-len 5]]}]
      [:age   {:id :age   :type :number :validation [[:min 0] [:max 120]]}]
      [:notes {:id :notes :type :string}]]}})           ; no :validation → no error reaction

(defn- sess [changes]
  (impl/apply-changes (impl/create-session (validation/compile-validations form) {})
                      changes))

(deftest required-and-length-and-range-validators
  (testing "a required field is invalid while blank, valid once filled"
    (is (= "is required" (impl/value (sess []) :name-error)))
    (is (nil? (impl/value (sess [[:name "Ada"]]) :name-error))))
  (testing "max-len fails past the limit"
    (is (some? (impl/value (sess [[:name "Lovelace"]]) :name-error))))
  (testing "numeric range validators"
    (is (some? (impl/value (sess [[:age -1]]) :age-error)))
    (is (some? (impl/value (sess [[:age 200]]) :age-error)))
    (is (nil?  (impl/value (sess [[:age 30]]) :age-error))))
  (testing "an optional field with no :validation gets no error reaction"
    (is (nil? (impl/value (sess []) :notes-error)))))

(deftest aggregate-validity-reaction
  (testing ":valid? is false while any error is present, true when all clear"
    (is (false? (impl/value (sess []) :valid?)))                 ; name required, blank
    (is (false? (impl/value (sess [[:name "Ada"] [:age 200]]) :valid?)))
    (is (true?  (impl/value (sess [[:name "Ada"] [:age 30]]) :valid?)))))

(deftest conditional-validation-with-validate-when
  (let [f '{:id :cond
            :data
            {:model
             [[:flag   {:id :flag   :type :boolean}]
              [:reason {:id :reason :type :string
                        :validation [:required] :validate-when :flag}]]}}
        sess (fn [changes] (impl/apply-changes
                            (impl/create-session (validation/compile-validations f) {})
                            changes))]
    (testing "the field is only validated when the guard is truthy"
      (is (nil? (impl/value (sess []) :reason-error)))          ; flag falsey → skip
      (is (= "is required" (impl/value (sess [[:flag true]]) :reason-error)))
      (is (nil? (impl/value (sess [[:flag true] [:reason "x"]]) :reason-error))))))
