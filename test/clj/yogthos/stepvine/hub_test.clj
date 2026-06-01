(ns yogthos.stepvine.hub-test
  "Phase 3: the connection hub fans signal patches out to every connection on a
   document. Uses the Datastar test recorder as a stand-in SSE generator."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.hub :as hub]
   [starfederation.datastar.clojure.adapter.test :as ds-test]))

(defn- events [recorder] @(:!rec recorder))

(deftest broadcast-reaches-all-connections-on-a-document
  (let [h (atom {})
        a (ds-test/->sse-recorder)
        b (ds-test/->sse-recorder)
        c (ds-test/->sse-recorder)]
    (hub/register! h "doc1" "a" a "u1")
    (hub/register! h "doc1" "b" b "u2")
    (hub/register! h "doc2" "c" c "u3")

    (testing "per-document connection tracking"
      (is (= 2 (hub/connection-count h "doc1")))
      (is (= 1 (hub/connection-count h "doc2")))
      (is (= #{"u1" "u2"} (hub/users h "doc1"))))

    (testing "broadcast hits both connections on doc1 as a patch-signals event"
      (hub/broadcast-signals! h "doc1" {"bmi" 25 "overweight" true})
      (doseq [r [a b]]
        (is (str/includes? (first (events r)) "datastar-patch-signals"))
        (is (str/includes? (first (events r)) "bmi")))
      (testing "and not the connection on doc2"
        (is (empty? (events c)))))

    (testing "unregister removes a connection"
      (hub/unregister! h "doc1" "a")
      (is (= 1 (hub/connection-count h "doc1"))))))

;; --- Resilience: heartbeat keep-alive (P2) --------------------------------

(deftest ping-reaches-every-connection
  (let [h (atom {})
        a (ds-test/->sse-recorder)
        b (ds-test/->sse-recorder)]
    (hub/register! h "d1" "a" a "u1")
    (hub/register! h "d2" "b" b "u2")
    (hub/ping-all! h)
    (testing "each connection (across documents) gets a no-op signal patch"
      (doseq [r [a b]]
        (is (str/includes? (last @(:!rec r)) "datastar-patch-signals"))))))

(deftest heartbeat-component-pings-then-stops
  (let [h    (atom {})
        r    (ds-test/->sse-recorder)
        _    (hub/register! h "d1" "c" r "u")
        comp (ig/init-key :datastar/heartbeat {:hub h :interval-ms 40})]
    (Thread/sleep 130)                       ; ~3 intervals
    (ig/halt-key! :datastar/heartbeat comp)
    (testing "it pinged the connection while running"
      (is (pos? (count @(:!rec r)))))
    (testing "halt stops the worker"
      (is (false? @(:running comp)))
      (let [n @(:!rec r)]
        (Thread/sleep 90)
        (is (= (count n) (count @(:!rec r))))))))
