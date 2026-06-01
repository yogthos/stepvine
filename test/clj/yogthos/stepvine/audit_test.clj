(ns yogthos.stepvine.audit-test
  "Phase 8 (§15.4): durable append-only audit log."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.audit :as audit]))

(deftest record-stamps-and-appends
  (let [store (audit/store)
        e     (audit/record! store {:doc-id "d" :by "u" :action :field/save
                                    :path [:weight] :before 70 :after 72})]
    (testing "an entry is stamped with id + at and appended"
      (is (string? (:id e)))
      (is (number? (:at e)))
      (is (= :field/save (:action e)))
      (is (= [e] (audit/entries store))))))

(deftest for-document-filters-newest-first
  (let [store (audit/store)]
    (audit/record! store {:doc-id "a" :at-hint 1 :action :field/save :path [:x] :after 1})
    (Thread/sleep 2)
    (audit/record! store {:doc-id "a" :action :state/transition :before :draft :after :review})
    (audit/record! store {:doc-id "b" :action :field/save :path [:y] :after 2})
    (let [es (audit/for-document store "a")]
      (testing "only this document's entries, newest first"
        (is (= 2 (count es)))
        (is (every? #(= "a" (:doc-id %)) es))
        (is (= :state/transition (:action (first es))))))))

(deftest record-never-throws
  (testing "a write failure is swallowed (returns nil), never thrown — an edit
            must not break because auditing failed"
    (is (nil? (audit/record! nil {:doc-id "d" :action :field/save})))))
