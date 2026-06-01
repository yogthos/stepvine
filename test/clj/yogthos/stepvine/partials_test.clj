(ns yogthos.stepvine.partials-test
  "Phase 9 (§15.9): reusable definition partials spliced into forms by id."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.partials :as partials]))

(def reg {:address [:c/section {:title "Address"}
                    [:c/input-field {:id :street :label "Street"}]
                    [:c/input-field {:id :city :label "City"}]]})

(deftest splice-replaces-include-nodes
  (testing "an {:include id} node is replaced by the partial's value"
    (is (= [:c/form {}
            [:c/section {:title "Address"}
             [:c/input-field {:id :street :label "Street"}]
             [:c/input-field {:id :city :label "City"}]]]
           (partials/splice reg [:c/form {} {:include :address}]))))
  (testing "splicing is recursive (nested includes resolve)"
    (is (= [:div [:c/section {:title "Address"}
                  [:c/input-field {:id :street :label "Street"}]
                  [:c/input-field {:id :city :label "City"}]]]
           (partials/splice reg [:div {:include :address}]))))
  (testing "an unknown include is left untouched (and flagged elsewhere)"
    (is (= [:div {:include :missing}]
           (partials/splice reg [:div {:include :missing}]))))
  (testing "forms without includes pass through unchanged"
    (is (= [:c/form {} [:c/input-field {:id :x}]]
           (partials/splice reg [:c/form {} [:c/input-field {:id :x}]])))))
