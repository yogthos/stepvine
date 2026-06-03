(ns yogthos.stepvine.submission-test
  "Phase 8 (§15.5): per-view submission, approval log, snapshot, and the
   read-only-after-finalize status."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.documents :as documents]))

(defn- store-with [doc] (atom {(:id doc) doc}))

(deftest submit-records-approval-snapshot-and-locks
  (let [doc   (documents/create! (atom {}) :intake {:created-by "u1"})
        store (store-with doc)
        id    (:id doc)]
    (documents/submit! store id :default "u1" {:weight 72})
    (let [d (documents/get-document store id)]
      (testing "the view is marked submitted and the document is locked"
        (is (documents/submitted-for? d :default))
        (is (= :submitted (:status d)))
        (is (documents/locked? d)))
      (testing "an approval is appended with actor + view"
        (is (= [{:view :default :by "u1"}]
               (map #(select-keys % [:view :by]) (get-in d [:meta :approvals])))))
      (testing "an immutable snapshot is stored"
        (is (= {:weight 72} (-> d (get-in [:meta :reports]) first :snapshot))))
      (testing "submit is a meta change — it does NOT bump the data :rev (§oc:
                rev tracks data, so a client's own lifecycle action never self-stales)"
        (is (= 0 (:rev d)))
        (is (some? (get-in d [:meta :modified-at])))))))

(deftest revise-reopens-but-keeps-the-approval
  (let [doc   (documents/create! (atom {}) :intake {:created-by "u1"})
        store (store-with doc)
        id    (:id doc)]
    (documents/submit! store id :default "u1" {})
    (documents/revise! store id :default)
    (let [d (documents/get-document store id)]
      (testing "the view is reopened and the document editable again"
        (is (not (documents/submitted-for? d :default)))
        (is (= :in-progress (:status d)))
        (is (not (documents/locked? d))))
      (testing "the approval log is retained (append-only audit)"
        (is (= 1 (count (get-in d [:meta :approvals]))))))))

(deftest multi-view-document-stays-locked-until-all-reopened
  (let [doc   (documents/create! (atom {}) :intake {:created-by "u1"})
        store (store-with doc)
        id    (:id doc)]
    (documents/submit! store id :default "u1" {})
    (documents/submit! store id :summary "u1" {})
    (documents/revise! store id :default)
    (testing "still locked while another view remains submitted"
      (is (= :submitted (:status (documents/get-document store id)))))
    (documents/revise! store id :summary)
    (testing "editable once the last submitted view is reopened"
      (is (= :in-progress (:status (documents/get-document store id)))))))
