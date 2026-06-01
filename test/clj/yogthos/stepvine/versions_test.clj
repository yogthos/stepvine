(ns yogthos.stepvine.versions-test
  "Phase 7 (§15.1): the immutable form-version archive — content digest,
   write-once publishing, latest-published resolution, and draft exclusion."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.versions :as versions]))

(def form-v1 '{:id :widget :version 1
               :data {:model [[:a {:id :a :type :string}]]}})
(def form-v1-reordered '{:version 1 :id :widget
                         :data {:model [[:a {:type :string :id :a}]]}})
(def form-v1-changed '{:id :widget :version 1
                       :data {:model [[:a {:id :a :type :number}]]}})
(def form-v2 '{:id :widget :version 2
               :data {:model [[:a {:id :a :type :string}]
                              [:b {:id :b :type :string}]]}})

(deftest digest-is-content-stable-and-order-independent
  (testing "equal content → equal digest, regardless of map key order"
    (is (= (versions/digest form-v1) (versions/digest form-v1-reordered))))
  (testing "different content → different digest"
    (is (not= (versions/digest form-v1) (versions/digest form-v1-changed)))
    (is (not= (versions/digest form-v1) (versions/digest form-v2)))))

(deftest publish-and-resolve
  (let [a (versions/archive)]
    (let [r (versions/publish! a form-v1)]
      (is (= {:id :widget :version 1} (select-keys r [:id :version])))
      (is (string? (:digest r))))
    (versions/publish! a form-v2)
    (testing "latest-published returns the highest version number"
      (is (= 2 (versions/latest-version a :widget))))
    (testing "get-version returns the exact archived snapshot"
      (is (= form-v1 (versions/get-version a :widget 1)))
      (is (= form-v2 (versions/get-version a :widget 2))))
    (testing "list-versions returns all published version numbers, ascending"
      (is (= [1 2] (versions/list-versions a :widget))))
    (testing "missing form/version → nil"
      (is (nil? (versions/get-version a :widget 9)))
      (is (nil? (versions/latest-version a :nope))))))

(deftest publishing-is-idempotent-by-digest
  (let [a (versions/archive)]
    (versions/publish! a form-v1)
    (testing "re-publishing identical content is a no-op (no throw)"
      (is (= 1 (:version (versions/publish! a form-v1))))
      (is (= 1 (versions/latest-version a :widget))))))

(deftest superseded-versions-are-sealed-immutable
  (let [a (versions/archive)]
    (versions/publish! a form-v1)
    (versions/publish! a form-v2)                      ; v1 now sealed (a higher version exists)
    (testing "mutating a sealed (superseded) version throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (versions/publish! a form-v1-changed))))   ; same :version 1, new content
    (testing "the sealed version is unchanged"
      (is (= form-v1 (versions/get-version a :widget 1))))))

(deftest working-version-is-mutable-until-superseded
  (let [a (versions/archive)]
    (versions/publish! a form-v1)
    (testing "re-publishing the highest version with new content overwrites (working copy)"
      (versions/publish! a form-v1-changed)
      (is (= form-v1-changed (versions/get-version a :widget 1))))))

(deftest drafts-excluded-from-latest
  (let [a (versions/archive)]
    (versions/publish! a form-v1)
    (versions/publish! a (assoc form-v2 :draft? true))
    (testing "a draft version is archived but not the latest published"
      (is (= 1 (versions/latest-version a :widget)))
      (is (some? (versions/get-version a :widget 2))))))
