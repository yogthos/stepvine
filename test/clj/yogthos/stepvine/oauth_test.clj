(ns yogthos.stepvine.oauth-test
  "Phase 11 (§15.13): OAuth2 / OIDC login — URL building, id_token decoding, the
   callback flow (CSRF state + find-or-create + session), with no live provider."
  (:require
   [clojure.test :refer [deftest testing is]]
   [jsonista.core :as json]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.oauth :as oauth])
  (:import [java.util Base64]))

(defn- jwt [claims]
  (let [b64 #(.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                              (.getBytes (str %) "UTF-8"))]
    (str (b64 "{}") "." (b64 (json/write-value-as-string claims)) ".sig")))

(deftest authorize-url-builds-the-flow
  (let [u (oauth/authorize-url {:authorize-url "https://idp/auth" :client-id "cid"
                                :scopes ["openid" "email"]}
                               "STATE" "https://app/cb")]
    (is (.startsWith u "https://idp/auth?"))
    (doseq [frag ["response_type=code" "client_id=cid" "state=STATE"
                  "redirect_uri=https%3A%2F%2Fapp%2Fcb" "scope=openid+email"]]
      (is (clojure.string/includes? u frag)))))

(deftest decode-id-token-claims
  (let [claims (oauth/decode-claims (jwt {:sub "abc" :email "ada@x.io" :name "Ada"}))]
    (is (= "abc" (:sub claims)))
    (is (= "ada@x.io" (:email claims))))
  (testing "claims->profile shapes the OIDC profile"
    (is (= {:provider :google :subject "abc" :email "ada@x.io" :name "Ada"}
           (oauth/claims->profile :google {:sub "abc" :email "ada@x.io" :name "Ada"})))))

(deftest find-or-create-is-idempotent-per-subject
  (let [store (atom {})
        u1 (users/find-or-create-oauth! store {:provider :google :subject "s1" :email "a@b.c" :name "A"})
        u2 (users/find-or-create-oauth! store {:provider :google :subject "s1" :email "a@b.c" :name "A"})]
    (is (= (:id u1) (:id u2)))                    ; same federated identity → same user
    (is (= "A" (:display-name u1)))
    (is (nil? (:password-hash u1)))               ; password-less
    (is (= 1 (count @store)))))

(def providers
  {:mock {:mock? true :mock-profile {:provider :mock :subject "sub1" :email "demo@x.io" :name "Demo"}}})

(deftest callback-creates-session-on-valid-state
  (let [users-store (atom {})
        handler     (oauth/callback providers users-store {})
        resp        (handler {:path-params {:provider "mock"}
                              :params {:code "c" :state "S"}
                              :session {:oauth-state "S"}})]
    (testing "redirects home with a user session"
      (is (= 303 (:status resp)))
      (is (= "/" (get-in resp [:headers "Location"])))
      (is (some? (get-in resp [:session :user-id])))
      (is (nil? (get-in resp [:session :oauth-state]))))   ; state cleared
    (testing "the federated user now exists"
      (is (some? (users/find-by-oauth users-store :mock "sub1"))))))

(deftest callback-rejects-state-mismatch
  (let [handler (oauth/callback providers (atom {}) {})
        resp    (handler {:path-params {:provider "mock"}
                          :params {:code "c" :state "WRONG"}
                          :session {:oauth-state "S"}})]
    (is (= 303 (:status resp)))
    (is (clojure.string/includes? (get-in resp [:headers "Location"]) "error=oauth-state"))
    (is (nil? (:session resp)))))

(deftest callback-uses-injected-exchange-for-real-providers
  (let [users-store (atom {})
        real        {:google {:token-url "https://idp/token" :client-id "cid"}}
        exchanged   (atom nil)
        exchange-fn (fn [_p _cfg code _ru] (reset! exchanged code)
                      {:provider :google :subject "g1" :email "g@x.io" :name "G"})
        handler     (oauth/callback real users-store {:exchange-fn exchange-fn})
        resp        (handler {:path-params {:provider "google"}
                              :params {:code "AUTHCODE" :state "S"}
                              :session {:oauth-state "S"}})]
    (is (= "AUTHCODE" @exchanged))                 ; injected exchange was used
    (is (some? (get-in resp [:session :user-id])))
    (is (some? (users/find-by-oauth users-store :google "g1")))))
