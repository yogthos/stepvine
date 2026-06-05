(ns yogthos.stepvine.web.auth
  "Authentication HTTP layer — login / register / logout handlers and the
   wrap-auth middleware that gates the app behind a session.

   These are plain Ring handlers (not Mycelium workflows) so they can set/clear
   the signed-cookie `:session` directly. Identity then flows through the session
   cookie: edit/lock/SSE endpoints read the user from `:session`, not from a
   client-supplied signal."
  (:require
   [clojure.string :as str]
   [ring.util.response :as resp]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.layout :as layout]
   [yogthos.stepvine.web.security :as security]))

;; --- Pages ----------------------------------------------------------------

(defn- auth-page [title form]
  ;; pre-login pages: brand + footer chrome, no user/breadcrumbs
  (layout/page
   {:title title
    :head [:style (str ".sv-content{max-width:22rem} label{display:block;margin:.6rem 0 .15rem;color:#374151}"
                       ".sv-content input{width:100%;padding:.4rem .5rem;border:1px solid #d1d5db;border-radius:.375rem}"
                       ".sv-content button{margin-top:1rem;padding:.45rem .9rem;background:#2563eb;color:#fff;border:0;border-radius:.375rem;cursor:pointer}"
                       ".err{color:#b91c1c;background:#fef2f2;border:1px solid #fecaca;padding:.4rem .6rem;border-radius:.375rem}"
                       ".oauth{display:inline-block;margin-top:.6rem}")]}
   form))

(defn login-page [{:keys [error providers]}]
  (auth-page "Sign in"
             [:div
              [:h1 "Sign in"]
              (when error [:p.err error])
              [:form {:method "post" :action "/login"}
               (security/csrf-field)
               [:label {:for "username"} "Username"]
               [:input {:id "username" :name "username" :autofocus true}]
               [:label {:for "password"} "Password"]
               [:input {:id "password" :name "password" :type "password"}]
               [:button "Sign in"]]
              ;; OAuth/OIDC sign-in (§15.13) — one link per configured provider
              (when (seq providers)
                [:p (for [{:keys [id label]} providers]
                      [:a.oauth {:href (str "/oauth/" (name id))} (str "Sign in with " label)])])
              [:p "No account? " [:a {:href "/register"} "Register"]]]))

(defn register-page [{:keys [error]}]
  (auth-page "Register"
             [:div
              [:h1 "Register"]
              (when error [:p.err error])
              [:form {:method "post" :action "/register"}
               (security/csrf-field)
               [:label {:for "username"} "Username"]
               [:input {:id "username" :name "username" :autofocus true}]
               [:label {:for "display-name"} "Display name"]
               [:input {:id "display-name" :name "display-name"}]
               [:label {:for "password"} "Password"]
               [:input {:id "password" :name "password" :type "password"}]
               [:button "Create account"]]
              [:p "Have an account? " [:a {:href "/login"} "Sign in"]]]))

(defn- html [body status]
  (-> (resp/response body) (resp/content-type "text/html") (resp/status status)))

(defn- login! [req user]
  (-> (resp/redirect "/" :see-other)
      (assoc :session (assoc (:session req) :user-id (:id user)))))

;; --- Handlers -------------------------------------------------------------

(defn login-get
  "Returns the GET /login handler, rendering provider sign-in buttons."
  [providers]
  (fn [_req] (html (login-page {:providers providers}) 200)))
(defn register-get [_req] (html (register-page {}) 200))

(defn login-post
  ([users-store] (login-post users-store nil))
  ([users-store providers]
   (fn [req]
     (let [{:keys [username password]} (:params req)]
       (if-let [user (auth/authenticate users-store username password)]
         (login! req user)
         (html (login-page {:error "Invalid username or password." :providers providers}) 401))))))

(defn register-post [users-store]
  (fn [req]
    (let [{:keys [username password display-name]} (:params req)]
      (cond
        (or (str/blank? username) (str/blank? password))
        (html (register-page {:error "Username and password are required."}) 400)

        (users/find-by-username users-store username)
        (html (register-page {:error "That username is taken."}) 409)

        :else
        (login! req (users/create! users-store
                                   {:username username :password password
                                    :display-name display-name}))))))

(defn logout [_req]
  (assoc (resp/redirect "/login" :see-other) :session nil))

;; --- Middleware -----------------------------------------------------------

(def ^:private public-paths #{"/login" "/register"})

(defn- public?
  "Pre-login routes that bypass the gate: the auth pages, the OAuth flow, and the
   /api subtree (its own concern)."
  [req]
  (let [uri (or (:uri req) "")]
    (or (contains? public-paths uri)
        (str/starts-with? uri "/api")
        (str/starts-with? uri "/oauth"))))

(defn wrap-auth
  "Gate every non-public route behind a *valid* session: the request must resolve
   to a user that still exists in the store. A session carrying a `:user-id` that
   no longer matches a user (the user was deleted, or the store was reset) is NOT
   treated as authenticated — merely holding an id is not enough; the stale
   session is cleared and the visitor is sent to /login. The resolved user is
   threaded onto the request as `:identity` for downstream handlers."
  [users-store handler]
  (fn [req]
    (if (public? req)
      (handler req)
      (if-let [user (auth/current-user users-store req)]
        (handler (assoc req :identity user))
        (-> (resp/redirect "/login" :see-other)
            (assoc :session nil))))))            ; drop the stale/invalid session
