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
   [yogthos.stepvine.sources :as sources]
   [yogthos.stepvine.editor.impl :as impl]))

(def ^:private patient-directory
  {"p1" {:given "Ada" :family "Lovelace" :dob "1815-12-10"}})

(defn- intake-resolve
  "Resolve the intake form's named sources against a stub patient client."
  [form]
  (let [ctx (imports/source-ctx {:patient-client #(get patient-directory %)})]
    (fn [sid] (sources/resolve-source ctx (get-in form [:sources sid])))))

;; --- DB-sourced dropdown options -----------------------------------------

(deftest dropdown-options-resolve-from-the-store
  (let [store {:clinics/active [{:value "north" :label "North Clinic"}]}
        sess  (impl/create-session (forms/load-form "intake") {})
        resolved (options/resolve-field-options store (:field-opts sess))]
    (is (= [{:value "north" :label "North Clinic"}] (get resolved :clinic)))
    (testing "fields without an :options source are not resolved"
      (is (not (contains? resolved :kg))))))

;; --- External-service imports --------------------------------------------

(defn- read-from [m] (fn [path] (get m (first path))))

(deftest imports-are-lazy-and-diff-based
  (let [form    (forms/load-form "intake")
        resolve (intake-resolve form)]
    (testing "an import runs only on its declared trigger (lazy)"
      (is (empty? (imports/run (:imports form) (imports/event-trigger :kg)
                               resolve (read-from {:patient-id "p1"}))))
      (is (= #{[:fname "Ada"] [:lname "Lovelace"] [:dob "1815-12-10"]}
             (set (imports/run (:imports form) (imports/event-trigger :patient-id)
                               resolve (read-from {:patient-id "p1"}))))))
    (testing "diff-based: fields already at the fetched value emit no change"
      (is (= #{[:lname "Lovelace"] [:dob "1815-12-10"]}
             (set (imports/run (:imports form) (imports/event-trigger :patient-id)
                               resolve (read-from {:patient-id "p1" :fname "Ada"}))))))
    (testing "a SET of triggers (the transaction change-set) fires any matching import"
      ;; an import reacts when ANY field in the change-set is one of its triggers,
      ;; so it fires for a cascaded change too
      (is (empty? (imports/run (:imports form) #{:event/kg :event/m}
                               resolve (read-from {:patient-id "p1"}))))
      (is (= #{[:fname "Ada"] [:lname "Lovelace"] [:dob "1815-12-10"]}
             (set (imports/run (:imports form) #{:event/kg :event/patient-id}
                               resolve (read-from {:patient-id "p1"}))))))))

(deftest setting-trigger-field-hydrates-from-service
  (let [form    (forms/load-form "intake")
        docs    (atom {})
        h       (atom {})
        mgr     (ig/init-key :session/manager {:documents docs :hub h})
        resolve (intake-resolve form)]
    (session/ensure-document! mgr "intake" form {})
    (session/apply-change! mgr "intake" [[:patient-id "p1"]])
    ;; the import the apply-field cell runs after a trigger change
    (let [changes (imports/run (:imports form) (imports/event-trigger :patient-id)
                               resolve (fn [path] (session/value mgr "intake" (first path))))]
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
