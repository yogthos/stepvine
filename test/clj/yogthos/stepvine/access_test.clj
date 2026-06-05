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

(deftest team-membership
  (let [store (access/store)]
    (testing "an open form (no roles) has no team — documents stay owner-private"
      (is (not (access/team-member? store clerk :intake)))
      (is (not (access/team-member? store nobody :intake))))
    (testing "a role-restricted form's role holders are its team (workflow handlers)"
      (access/set-form-roles! store :intake [:clerk :nurse])
      (is (access/team-member? store clerk :intake))
      (is (access/team-member? store nurse :intake))
      (is (not (access/team-member? store nobody :intake))))
    (testing "admin is a team member of role-restricted forms"
      (is (access/team-member? store admin :intake)))       ; :intake is restricted above
    (testing "but an open form (no roles) has no team — not even admin"
      (is (not (access/team-member? store admin :ticket))))))

(deftest all-roles-in-use
  (let [store (access/store)]
    (access/set-form-roles! store :intake [:clerk :nurse])
    (access/set-form-roles! store :ticket [:nurse :reviewer])
    (testing "the union of assigned roles + :admin, for the admin UI's role picker"
      (is (= #{:admin :clerk :nurse :reviewer} (access/known-roles store))))))

(deftest per-role-view-access
  (let [store (access/store)]
    (access/set-form-access! store :report {:reviewer #{:default :preview}
                                            :approver #{:preview}})
    (testing "form-level access still derives from the role keys"
      (is (= #{:reviewer :approver} (access/form-roles store :report)))
      (is (access/can-access? store {:roles #{:reviewer}} :report))
      (is (not (access/can-access? store {:roles #{:other}} :report))))
    (testing "a role sees only the views mapped to it"
      (is (access/view-permitted? store {:roles #{:reviewer}} :report :default))
      (is (access/view-permitted? store {:roles #{:reviewer}} :report :preview))
      (is (not (access/view-permitted? store {:roles #{:approver}} :report :default)))
      (is (access/view-permitted? store {:roles #{:approver}} :report :preview)))
    (testing "admin sees every view; an unmapped user sees none"
      (is (access/view-permitted? store admin :report :default))
      (is (not (access/view-permitted? store nobody :report :preview))))
    (testing "a role with NO views listed (#{}) sees all views (form-level grant)"
      (access/set-form-access! store :report {:lead #{}})
      (is (access/view-permitted? store {:roles #{:lead}} :report :default))
      (is (access/view-permitted? store {:roles #{:lead}} :report :preview)))
    (testing "an open form (no access map) permits every view"
      (is (access/view-permitted? store nobody :open-form :anything)))
    (testing "legacy set-form-roles! grants a role all views"
      (access/set-form-roles! store :legacy [:clerk])
      (is (access/view-permitted? store clerk :legacy :default))
      (is (access/view-permitted? store clerk :legacy :preview)))))
