(ns yogthos.stepvine.sources-test
  "Phase 9 (§15.6): one :kind multimethod resolving every pluggable source to a
   uniform fn, with a host allowlist guarding remote calls."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.sources :as sources]))

(def opts [{:value "north" :label "North Clinic"}
           {:value "south" :label "South Clinic"}
           {:value "harbor" :label "Harbor Clinic"}])

(deftest static-and-store-option-sources
  (testing ":static returns its inline data, and filters by a query on the label"
    (let [r (sources/resolve-source {} {:kind :static :data opts})]
      (is (= opts (r)))
      (is (= [{:value "north" :label "North Clinic"}] (r "north")))
      (is (= 2 (count (r "or"))))                ; case-insensitive substring: North, Harbor
      (is (= 3 (count (r "clinic"))))))          ; matches all labels
  (testing ":options pulls a named set from the options store"
    (let [r (sources/resolve-source {:options-store {:clinics/active opts}}
                                    {:kind :options :source :clinics/active})]
      (is (= opts (r)))
      (is (= 1 (count (r "harbor")))))))

(deftest client-fetch-source
  (testing ":client calls a configured client fn with the keyed param"
    (let [r (sources/resolve-source
             {:clients {:patient (fn [mrn] {:given "Ada" :mrn mrn})}}
             {:kind :client :client :patient :key :mrn})]
      (is (= {:given "Ada" :mrn "x1"} (r {:mrn "x1"}))))))

(deftest http-source-enforces-host-allowlist
  (let [captured (atom nil)
        req-fn   (fn [req] (reset! captured req) {:ok true})
        ctx      {:request-fn req-fn :base-url "https://clinics.internal"}]
    (testing "a host not on the allowlist is refused (SSRF guard)"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sources/resolve-source ctx {:kind :http :url "/p" :host-allow ["evil.test"]}))))
    (testing "an allowed host resolves; params are allowlist-filtered into the request"
      (let [r (sources/resolve-source ctx {:kind :http :method :get :url "/patient"
                                           :query-key :mrn :host-allow ["clinics.internal"]
                                           :allow #{:mrn}})]
        (is (= {:ok true} (r {:mrn "x1" :secret "drop-me"})))
        (is (= :get (:method @captured)))
        (is (= "https://clinics.internal/patient" (:url @captured)))
        (is (= {:mrn "x1"} (:query-params @captured)))))))   ; :secret filtered out

(deftest unknown-kind-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (sources/resolve-source {} {:kind :nope}))))
