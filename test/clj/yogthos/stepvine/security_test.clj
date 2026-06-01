(ns yogthos.stepvine.security-test
  "P3: per-document access control + CSRF middleware."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.web.security :as security]))

(deftest document-access-control
  (let [store (atom {})
        d     (documents/create! store :bmi {:created-by "owner1"})]
    (testing "owner can access; others cannot until shared"
      (is (documents/can-access? d "owner1"))
      (is (not (documents/can-access? d "friend")))
      (is (not (documents/can-access? d nil))))
    (testing "sharing grants access"
      (documents/share! store (:id d) "friend")
      (is (documents/can-access? (documents/get-document store (:id d)) "friend")))
    (testing "accessible-by lists owned + shared only"
      (is (= 1 (count (documents/accessible-by store "owner1"))))
      (is (= 1 (count (documents/accessible-by store "friend"))))
      (is (= 0 (count (documents/accessible-by store "stranger")))))))

(deftest wrap-doc-access-enforces-acl
  (let [store (atom {})
        d     (documents/create! store :bmi {:created-by "u1"})
        _     (documents/share! store (:id d) "u2")
        ok    (constantly {:status 200 :body "ok"})
        h     ((security/wrap-doc-access store) ok)
        req   (fn [id uid] {:path-params {:id id} :session {:user-id uid}})]
    (is (= 200 (:status (h (req (:id d) "u1")))) "owner")
    (is (= 200 (:status (h (req (:id d) "u2")))) "shared")
    (is (= 403 (:status (h (req (:id d) "u3")))) "no access")
    (is (= 404 (:status (h (req "missing" "u1")))) "no such doc")))

(deftest wrap-require-datastar-blocks-tokenless-posts
  (let [h (security/wrap-require-datastar (constantly {:status 200}))]
    (is (= 200 (:status (h {:request-method :get :headers {}})))
        "GET always allowed")
    (is (= 403 (:status (h {:request-method :post :headers {}})))
        "POST without Datastar-Request header is blocked")
    (is (= 200 (:status (h {:request-method :post :headers {"datastar-request" "true"}})))
        "POST with the header is allowed")))
