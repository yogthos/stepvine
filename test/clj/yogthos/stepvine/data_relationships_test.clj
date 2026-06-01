(ns yogthos.stepvine.data-relationships-test
  "Phase 4: data relationships beyond derived fields — DB-sourced dropdown
   options, external-service imports, and cross-field validation reactions."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.imports :as imports]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.editor.impl :as impl]))

;; --- DB-sourced dropdown options -----------------------------------------

(deftest dropdown-options-resolve-from-the-store
  (let [store {:clinics/active [{:value "north" :label "North Clinic"}]}
        sess  (impl/create-session (forms/load-form "intake") {})
        resolved (options/resolve-field-options store (:field-opts sess))]
    (is (= [{:value "north" :label "North Clinic"}] (get resolved :clinic)))
    (testing "fields without an :options source are not resolved"
      (is (not (contains? resolved :kg))))))

;; --- External-service imports --------------------------------------------

(deftest imports-map-service-data-to-fields
  (let [form (forms/load-form "intake")]
    (testing "trigger lookup finds the import keyed by its trigger field"
      (let [cfg (imports/import-for-trigger form :patient-id)]
        (is (= :patient-id (:trigger cfg)))))
    (testing "no import for an unrelated field"
      (is (nil? (imports/import-for-trigger form :kg))))
    (testing "service data is mapped into [[field value] ...] changes"
      (let [cfg (imports/import-for-trigger form :patient-id)
            data {:given "Ada" :family "Lovelace" :dob "1815-12-10"}]
        (is (= #{[:fname "Ada"] [:lname "Lovelace"] [:dob "1815-12-10"]}
               (set (imports/mapped-changes data (:mapping cfg)))))))
    (testing "nil service result yields no changes"
      (is (empty? (imports/mapped-changes nil {:fname [:given]}))))))

(deftest setting-trigger-field-hydrates-from-service
  (let [form    (forms/load-form "intake")
        docs    (atom {})
        h       (atom {})
        mgr     (ig/init-key :session/manager {:documents docs :hub h})
        ;; stand-in for :clients/patient
        client  (fn [id] (get {"p1" {:given "Ada" :family "Lovelace" :dob "1815-12-10"}} id))]
    (session/ensure-document! mgr "intake" form {})
    (session/apply-change! mgr "intake" [[:patient-id "p1"]])
    ;; simulate the import the apply-field cell runs after a trigger change
    (let [cfg (imports/import-for-trigger form :patient-id)
          changes (imports/mapped-changes (client "p1") (:mapping cfg))]
      (session/apply-change! mgr "intake" changes))
    (is (= "Ada"        (session/value mgr "intake" :fname)))
    (is (= "Lovelace"   (session/value mgr "intake" :lname)))
    (is (= "1815-12-10" (session/value mgr "intake" :dob)))))

;; --- Cross-field validation reactions ------------------------------------

(deftest validation-reactions-and-aggregate-validity
  (let [form (forms/load-form "intake")
        s    (impl/create-session form {})]
    (testing "empty is valid (no values entered)"
      (is (nil?  (impl/value s :weight-error)))
      (is (true? (impl/value s :form-valid?))))
    (testing "an out-of-range weight produces an error and invalidates the form"
      (let [s (impl/apply-changes s [[:kg -5]])]
        (is (= "Weight must be positive" (impl/value s :weight-error)))
        (is (false? (impl/value s :form-valid?)))))
    (testing "valid values clear the error"
      (let [s (impl/apply-changes s [[:kg 80] [:m 1.8]])]
        (is (nil?  (impl/value s :weight-error)))
        (is (true? (impl/value s :form-valid?)))))))
