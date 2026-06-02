(ns yogthos.stepvine.index-test
  "Phase 11 (§15.13): index lookups — resolve an external key into an entity."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.index :as index]))

(def ctx {:clients {:patient (fn [mrn] (get {"p1" {:given "Ada" :family "Lovelace"}} mrn))}})
(def spec {:kind :client :client :patient :key :mrn :into :patient-id})

(deftest lookup-resolves-an-entity
  (testing "a known key resolves to its entity"
    (let [r (index/lookup ctx spec "p1")]
      (is (:found? r))
      (is (= "p1" (:key r)))
      (is (= "Ada" (get-in r [:entity :given])))))
  (testing "an unknown key is not found"
    (let [r (index/lookup ctx spec "nope")]
      (is (not (:found? r)))
      (is (nil? (:entity r))))))

(deftest preview-pairs
  (testing "preview pulls labelled fields from the entity in :preview order"
    (is (= [["given" "Ada"] ["family" "Lovelace"]]
           (index/preview {:given "Ada" :family "Lovelace" :dob "1815"}
                          [:given :family])))))
