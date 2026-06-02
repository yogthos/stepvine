(ns yogthos.stepvine.core-test
  "Integration: auth gate + per-document access control + CSRF on the real handler."
  (:require
   [byte-streams :as bs]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [peridot.core :as p]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.users :as users]
   yogthos.stepvine.components   ; register widget render methods
   [yogthos.stepvine.test-utils :refer [system-state system-fixture]]))

(use-fixtures :once (system-fixture))

(defn- handler [] (:handler/ring (system-state)))
(defn- body   [ctx] (bs/to-string (:body (:response ctx))))
(defn- status [ctx] (:status (:response ctx)))

(defn- anti-forgery-token [ctx]
  (second (re-find #"__anti-forgery-token\"[^>]*?value=\"([^\"]+)\"" (body ctx))))

(defn- login
  "An authenticated peridot session for the seeded admin (handles the CSRF token)."
  []
  (let [ctx (-> (p/session (handler)) (p/request "/login"))
        tok (anti-forgery-token ctx)]
    (p/request ctx "/login" :request-method :post
               :params {:username "admin" :password "admin"
                        :__anti-forgery-token tok})))

(deftest unauthenticated-is-redirected-to-login
  (let [ctx (-> (p/session (handler)) (p/request "/" :request-method :get))]
    (is (#{302 303} (status ctx)))
    (is (str/includes? (get-in ctx [:response :headers "Location"]) "/login"))))

(deftest login-page-is-public
  (let [ctx (-> (p/session (handler)) (p/request "/login" :request-method :get))]
    (is (= 200 (status ctx)))
    (is (str/includes? (body ctx) "Sign in"))))

(deftest landing-after-login
  (let [ctx (p/request (login) "/" :request-method :get)]
    (is (= 200 (status ctx)))
    (is (str/includes? (body ctx) "sv-brand"))          ; shared navbar chrome present
    (is (str/includes? (body ctx) "/form/bmi/new"))
    (is (str/includes? (body ctx) "Sign out"))))

(deftest owner-can-render-their-document
  (let [docs  (:store/documents (system-state))
        users (:store/users (system-state))
        admin (users/find-by-username users "admin")
        doc   (documents/create! docs :bmi {:created-by (:id admin)})
        ctx   (p/request (login) (str "/doc/" (:id doc)) :request-method :get)]
    (is (= 200 (status ctx)))
    (is (str/includes? (body ctx) "BMI Calculator"))
    (is (str/includes? (body ctx) "data-bind=\"kg\""))
    (is (str/includes? (body ctx) "data-init="))))

(deftest non-owner-without-share-is-forbidden
  (let [docs  (:store/documents (system-state))
        bob   (users/create! (:store/users (system-state))
                             {:username (str "bob-" (System/nanoTime)) :password "x"})
        doc   (documents/create! docs :bmi {:created-by (:id bob)})         ; owned by bob, not admin
        ctx   (p/request (login) (str "/doc/" (:id doc)) :request-method :get)]
    (is (= 403 (status ctx)))))