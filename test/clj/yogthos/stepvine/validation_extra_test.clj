(ns yogthos.stepvine.validation-extra-test
  "Richer declarative validators (parity stepvine-eht): cross-field date
   comparison, field-must-equal, :contains, :digits-only — compiled to error
   reactions and run through a live session."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.validation :as v]))

(deftest cross-field-reaction-threads-the-other-field
  (let [r (v/field-error-reaction :checkout [[:after :checkin]] nil)]
    (testing "the reaction takes the referenced field as an extra arg"
      (is (= [:checkout :checkin] (:args r)))
      (is (= '[v checkin] (second (:fn r)))))))   ; (fn [v checkin] …)

(def ^:private form
  (forms/prepare-form {}
   '{:id :event :version 1
     :data {:model [[:checkin  {:id :checkin  :type :string}]
                    [:checkout {:id :checkout :type :string :validation [[:after :checkin]]}]
                    [:email    {:id :email    :type :string}]
                    [:confirm  {:id :confirm  :type :string :validation [[:field-must-equal :email]]}]
                    [:code     {:id :code     :type :string :validation [:digits-only]}]
                    [:tags     {:id :tags     :type :array  :validation [[:contains "urgent"]]}]]}
     :views {:default {:opts {} :markup [:div]}}}))

(deftest validators-run-end-to-end
  (let [s0 (impl/create-session form {})]
    (testing ":after — date must be after another field"
      (is (= "must be after checkin"
             (impl/value (impl/apply-changes s0 [[:checkin "2026-06-10"] [:checkout "2026-06-05"]]) :checkout-error)))
      (is (nil? (impl/value (impl/apply-changes s0 [[:checkin "2026-06-01"] [:checkout "2026-06-05"]]) :checkout-error)))
      (is (nil? (impl/value (impl/apply-changes s0 [[:checkout "2026-06-05"]]) :checkout-error))))  ; checkin unset -> no error
    (testing ":field-must-equal — confirm matches email"
      (is (= "must match email"
             (impl/value (impl/apply-changes s0 [[:email "a@b.c"] [:confirm "x@y.z"]]) :confirm-error)))
      (is (nil? (impl/value (impl/apply-changes s0 [[:email "a@b.c"] [:confirm "a@b.c"]]) :confirm-error))))
    (testing ":digits-only"
      (is (= "must contain only digits" (impl/value (impl/apply-changes s0 [[:code "12a3"]]) :code-error)))
      (is (nil? (impl/value (impl/apply-changes s0 [[:code "1234"]]) :code-error))))
    (testing ":contains — an array field includes a value"
      (is (= "must include urgent" (impl/value (impl/apply-changes s0 [[:tags ["a" "b"]]]) :tags-error)))
      (is (nil? (impl/value (impl/apply-changes s0 [[:tags ["a" "urgent"]]]) :tags-error))))))
