(ns yogthos.stepvine.access-test
  "Role-based form access: a user may use a form if they are an admin, the form
   has no roles (open), or their roles intersect the form's."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.access :as access]))

(def admin   {:id "a" :roles #{:admin}})
(def clerk   {:id "c" :roles #{:clerk}})
(def nurse   {:id "n" :roles #{:nurse}})
(def nobody  {:id "z" :roles #{}})

(deftest form-role-assignment
  (let [store (access/store)]
    (testing "a form with no assignment is open to all"
      (is (= #{} (access/form-roles store :intake)))
      (is (access/can-access? store nobody :intake)))
    (testing "assigning roles restricts the form"
      (access/set-form-roles! store :intake [:clerk :nurse])
      (is (= #{:clerk :nurse} (access/form-roles store :intake)))
      (is (access/can-access? store clerk :intake))
      (is (access/can-access? store nurse :intake))
      (is (not (access/can-access? store nobody :intake))))
    (testing "admin can access any form regardless of roles"
      (is (access/can-access? store admin :intake)))
    (testing "accessible-forms filters by access"
      (access/set-form-roles! store :ticket [:nurse])
      (is (= [:intake] (access/accessible-forms store clerk [:intake :ticket])))
      (is (= [:intake :ticket] (access/accessible-forms store admin [:intake :ticket]))))))

(deftest all-roles-in-use
  (let [store (access/store)]
    (access/set-form-roles! store :intake [:clerk :nurse])
    (access/set-form-roles! store :ticket [:nurse :reviewer])
    (testing "the union of assigned roles + :admin, for the admin UI's role picker"
      (is (= #{:admin :clerk :nurse :reviewer} (access/known-roles store))))))
