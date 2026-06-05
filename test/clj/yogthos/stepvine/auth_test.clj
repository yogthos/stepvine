(ns yogthos.stepvine.auth-test
  "P1: user store + authentication."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.auth :as wauth]))

(deftest user-creation-hashes-password
  (let [store (atom {})
        user  (users/create! store {:username "ada" :password "secret" :display-name "Ada"})]
    (is (string? (:id user)))
    (testing "plaintext password is never stored"
      (is (not= "secret" (:password-hash user)))
      (is (not (re-find #"secret" (:password-hash user)))))
    (testing "username must be unique"
      (is (thrown? Exception (users/create! store {:username "ada" :password "x"}))))))

(deftest authentication
  (let [store (atom {})]
    (users/create! store {:username "ada" :password "secret"})
    (testing "correct credentials return the user"
      (is (= "ada" (:username (auth/authenticate store "ada" "secret")))))
    (testing "wrong password is rejected"
      (is (nil? (auth/authenticate store "ada" "wrong"))))
    (testing "unknown user is rejected"
      (is (nil? (auth/authenticate store "ghost" "secret"))))))

(deftest current-user-from-session
  (let [store (atom {})
        user  (users/create! store {:username "ada" :password "secret"})
        req   {:session {:user-id (:id user)}}]
    (is (auth/authenticated? req))
    (is (= "ada" (:username (auth/current-user store req))))
    (is (not (auth/authenticated? {:session {}})))))

(deftest wrap-auth-gates-on-an-existing-user
  (let [store (atom {})
        user  (users/create! store {:username "ada" :password "secret"})
        seen  (atom nil)
        app   (wauth/wrap-auth store (fn [req] (reset! seen req) {:status 200 :body "ok"}))]
    (testing "a valid session passes through and threads the resolved user as :identity"
      (let [resp (app {:uri "/" :session {:user-id (:id user)}})]
        (is (= 200 (:status resp)))
        (is (= "ada" (:username (:identity @seen))))))
    (testing "a session whose :user-id no longer exists is NOT authenticated"
      (let [resp (app {:uri "/doc/abc" :session {:user-id "deleted-id"}})]
        (is (= 303 (:status resp)) "redirected to /login")
        (is (= "/login" (get-in resp [:headers "Location"])))
        (is (and (contains? resp :session) (nil? (:session resp))) "stale session cleared")))
    (testing "no user-id at all → /login"
      (is (= "/login" (get-in (app {:uri "/" :session {}}) [:headers "Location"]))))
    (testing "public routes pass without any user"
      (is (= 200 (:status (app {:uri "/login"}))))
      (is (= 200 (:status (app {:uri "/oauth/google"})))))))
