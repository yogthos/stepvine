(ns yogthos.stepvine.web.oauth
  "OAuth2 / OIDC login (PLAN.md §15.13), alongside the bcrypt+session auth.

   Standard authorization-code flow:
     GET /oauth/:provider          → redirect to the provider's authorize URL
                                      (with a CSRF `state` stashed in the session)
     GET /oauth/:provider/callback → verify state, exchange the code
                                      *server-to-server* for an id_token, extract
                                      the OIDC subject + profile, find-or-create
                                      the federated user, and open a session.

   The token exchange is **injected** (`exchange-fn`), so the flow is unit-testable
   with no live provider; a `:mock?` provider short-circuits the external round
   trip for a self-contained local demo. JWKS signature verification is a noted
   hardening step — the id_token here arrives over a trusted TLS channel directly
   from the token endpoint."
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [ring.util.response :as resp]
   [yogthos.stepvine.users :as users])
  (:import
   [java.net URI URLEncoder]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
   [java.util Base64]))

;; --- helpers --------------------------------------------------------------

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- query-string [params]
  (str/join "&" (map (fn [[k v]] (str (name k) "=" (url-encode v))) params)))

(defn redirect-uri [req provider]
  (str (name (or (:scheme req) :http)) "://"
       (get-in req [:headers "host"] "localhost") "/oauth/" provider "/callback"))

(defn authorize-url
  "The provider's authorize URL for the authorization-code flow."
  [{:keys [authorize-url client-id scopes]} state redirect-uri]
  (str authorize-url "?"
       (query-string {:response_type "code"
                      :client_id     client-id
                      :redirect_uri  redirect-uri
                      :scope         (str/join " " (or scopes ["openid" "email" "profile"]))
                      :state         state})))

(defn decode-claims
  "Decode an id_token (a JWT) payload to its claims map — no signature check (the
   token is fetched server-to-server over TLS)."
  [id-token]
  (let [payload (second (str/split id-token #"\."))
        padded  (str payload (apply str (repeat (mod (- 4 (mod (count payload) 4)) 4) "=")))
        json*   (String. (.decode (Base64/getUrlDecoder) padded) "UTF-8")]
    (json/read-value json* json/keyword-keys-object-mapper)))

(defn claims->profile [provider claims]
  {:provider provider
   :subject  (:sub claims)
   :email    (:email claims)
   :name     (or (:name claims) (:email claims) (:sub claims))})

(defn default-exchange
  "Exchange an authorization `code` for an id_token at the provider's token
   endpoint (server-to-server), returning an OIDC profile. The real network path."
  [provider {:keys [token-url client-id client-secret] :as _cfg} code redirect-uri]
  (let [body (query-string {:grant_type    "authorization_code"
                            :code          code
                            :redirect_uri  redirect-uri
                            :client_id     client-id
                            :client_secret client-secret})
        req  (-> (HttpRequest/newBuilder (URI/create token-url))
                 (.header "Content-Type" "application/x-www-form-urlencoded")
                 (.header "Accept" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString body))
                 (.build))
        resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))
        tok  (json/read-value (.body resp) json/keyword-keys-object-mapper)]
    (claims->profile provider (decode-claims (:id_token tok)))))

;; --- handlers -------------------------------------------------------------

(defn start
  "GET /oauth/:provider — begin the flow. A `:mock?` provider skips the external
   provider and bounces straight to the callback (for local demo/tests)."
  [providers]
  (fn [req]
    (let [provider (get-in req [:path-params :provider])
          cfg      (get providers (keyword provider))
          state    (str (java.util.UUID/randomUUID))]
      (cond
        (nil? cfg)
        (resp/status (resp/response "Unknown OAuth provider") 404)

        (:mock? cfg)
        (-> (resp/redirect (str "/oauth/" provider "/callback?code=mock-code&state=" state) :see-other)
            (assoc :session (assoc (:session req) :oauth-state state)))

        :else
        (-> (resp/redirect (authorize-url cfg state (redirect-uri req provider)) :see-other)
            (assoc :session (assoc (:session req) :oauth-state state)))))))

(defn callback
  "GET /oauth/:provider/callback — verify state, exchange the code, open a session."
  [providers users-store {:keys [exchange-fn]}]
  (let [exchange-fn (or exchange-fn default-exchange)]
    (fn [req]
      (let [provider (get-in req [:path-params :provider])
            cfg      (get providers (keyword provider))
            {:keys [code state]} (:params req)
            expected (get-in req [:session :oauth-state])]
        (cond
          (nil? cfg)
          (resp/status (resp/response "Unknown OAuth provider") 404)

          (or (str/blank? state) (not= state expected))   ; CSRF guard
          (-> (resp/redirect "/login?error=oauth-state" :see-other) (assoc :session nil))

          :else
          (let [profile (if (:mock? cfg)
                          (:mock-profile cfg)
                          (exchange-fn (keyword provider) cfg code (redirect-uri req provider)))
                user    (users/find-or-create-oauth! users-store profile)]
            (-> (resp/redirect "/" :see-other)
                (assoc :session (-> (:session req)
                                    (dissoc :oauth-state)
                                    (assoc :user-id (:id user)))))))))))

(defn provider-list
  "Provider ids that offer a sign-in button, with their display labels."
  [providers]
  (map (fn [[id cfg]] {:id id :label (or (:label cfg) (str/capitalize (name id)))}) providers))
