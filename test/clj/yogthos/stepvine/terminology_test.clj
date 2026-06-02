(ns yogthos.stepvine.terminology-test
  "stepvine-9ox: terminology / value-set binding for coded fields."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.sources :as sources]
   [yogthos.stepvine.terminology :as term]))

(def ^:private compact-vs
  {:id :administrative-gender
   :system "http://hl7.org/fhir/administrative-gender"
   :concepts [{:code "male" :display "Male"} {:code "female" :display "Female"}]})

(def ^:private expand-response
  {:resourceType "ValueSet"
   :expansion {:contains [{:system "urn:sys" :code "A" :display "Alpha"}
                          {:system "urn:sys" :code "B" :display "Beta"}]}})

(deftest expand-compact-valueset
  (testing "compact {:system :concepts} → coded options carrying code/display/system"
    (is (= [{:value "male"   :label "Male"   :system "http://hl7.org/fhir/administrative-gender"}
            {:value "female" :label "Female" :system "http://hl7.org/fhir/administrative-gender"}]
           (term/expand compact-vs)))))

(deftest expand-fhir-expand-response
  (testing "a FHIR $expand response (:expansion/:contains) expands too"
    (is (= [{:value "A" :label "Alpha" :system "urn:sys"}
            {:value "B" :label "Beta"  :system "urn:sys"}]
           (term/expand expand-response)))))

(deftest expand-falls-back-to-code-when-no-display
  (is (= [{:value "x" :label "x" :system nil}]
         (term/expand {:concepts [{:code "x"}]}))))

(deftest expand-empty-shape-is-empty
  (is (= [] (term/expand {})))
  (is (= [] (term/expand {:system "s"}))))

(deftest load-dir-expands-every-valueset
  (let [loaded (term/load-dir "terminology")]
    (testing "the marital-status value set loads, expanded to coded options"
      (is (contains? loaded :marital-status))
      (let [opts (:marital-status loaded)]
        (is (= "Married" (:label (first (filter #(= "M" (:value %)) opts)))))
        (is (= "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus"
               (:system (first opts))))))))

(deftest load-dir-missing-is-empty
  (is (= {} (term/load-dir "no-such-terminology-dir"))))

;; --- :value-set source kind ------------------------------------------------

(deftest value-set-source-expands-inline
  (let [resolve (sources/resolve-source {} {:kind :value-set :expansion compact-vs})]
    (testing "no-arg returns all coded options"
      (is (= [{:value "male" :label "Male" :system "http://hl7.org/fhir/administrative-gender"}
              {:value "female" :label "Female" :system "http://hl7.org/fhir/administrative-gender"}]
             (resolve))))
    (testing "query arg filters by display (case-insensitive) — for typeahead"
      (is (= ["Female"] (map :label (resolve "fem")))))))

(deftest value-set-source-reads-named-from-store
  ;; the terminology store is folded into the options store; a named value set
  ;; resolves like any option set.
  (let [store   {:marital-status [{:value "M" :label "Married" :system "sys"}
                                  {:value "S" :label "Single" :system "sys"}]}
        resolve (sources/resolve-source {:options-store store}
                                        {:kind :value-set :value-set :marital-status})]
    (is (= ["Married" "Single"] (map :label (resolve))))
    (is (= ["Married"] (map :label (resolve "marr"))))))
