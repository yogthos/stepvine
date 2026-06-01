(ns yogthos.stepvine.auth-test
  "P1: user store + authentication."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.users :as users]))

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
