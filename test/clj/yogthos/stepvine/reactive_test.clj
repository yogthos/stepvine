(ns yogthos.stepvine.reactive-test
  "Phase 3: the end-to-end reactive loop. A change applied through the real
   :session/manager component recomputes derived fields/reactions and broadcasts
   only the changed signals to every connection on the document."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.session :as session]
   [starfederation.datastar.clojure.adapter.test :as ds-test]))

(defn- events [recorder] @(:!rec recorder))
(defn- last-event [recorder] (last (events recorder)))

(deftest change-recomputes-and-broadcasts-to-peers
  (let [form (forms/load-form "bmi")
        docs (atom {"bmi" {:id "bmi" :form-id :bmi :db {} :created-at 0}})
        h    (atom {})
        mgr  (ig/init-key :session/manager {:documents docs :hub h})
        peer-a (ds-test/->sse-recorder)
        peer-b (ds-test/->sse-recorder)]
    (session/ensure-document! mgr "bmi" form {})
    (hub/register! h "bmi" "a" peer-a "u1")
    (hub/register! h "bmi" "b" peer-b "u2")

    (testing "a weight+height change broadcasts the recomputed signals to all peers"
      (session/apply-change! mgr "bmi" [[:kg 100] [:m 2]])
      (doseq [r [peer-a peer-b]]
        (let [e (last-event r)]
          (is (str/includes? e "datastar-patch-signals"))
          (is (str/includes? e "bmi"))
          (is (str/includes? e "25"))
          (is (str/includes? e "overweight"))
          (is (str/includes? e "bmi_category")))))

    (testing "only changed signals are sent (delta), not untouched ones"
      ;; change only height; kg is unchanged so must not be in the patch
      (session/apply-change! mgr "bmi" [[:m 1]])
      (let [e (last-event peer-a)]
        (is (str/includes? e "bmi"))           ; recomputed 100
        (is (str/includes? e "\"m\""))         ; the field we changed
        (is (not (str/includes? e "\"kg\"")))));; unchanged, excluded

    (testing "the document store holds the latest snapshot"
      (is (= 100 (get-in (get @docs "bmi") [:db :patient :bmi]))))))
