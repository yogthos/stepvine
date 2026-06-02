(ns yogthos.stepvine.web.security
  "Authorization + CSRF middleware.

   - `wrap-doc-access`: per-document access control — the session user must own
     or be shared on the document named by the `:id` path param.
   - `wrap-require-datastar`: CSRF protection for the JSON/SSE document endpoints.
     Datastar sets `Datastar-Request: true` on every backend request; a cross-site
     form/image cannot set a custom header without a CORS preflight, so requiring
     it blocks cross-site state changes without any token plumbing.
   - HTML form POSTs (login/register/create/share/delete) are instead protected
     with ring's anti-forgery tokens (see `csrf-field`)."
  (:require
   [ring.middleware.anti-forgery :as af]
   [ring.util.response :as resp]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.users :as users]))

(defn- forbidden [msg]
  (-> (resp/response msg) (resp/status 403) (resp/content-type "text/plain")))

(defn wrap-doc-access
  "reitit middleware: 404 if the document doesn't exist, 403 if the session user
   can't access it. Access = owner or shared; plus, when `:forms`/`:users`/`:access`
   stores are supplied, a *team member* of the document's form (a role holder of a
   role-restricted form) — so workflow handlers can open queued documents."
  ([documents] (wrap-doc-access documents nil))
  ([documents {:keys [users access]}]
   (fn [handler]
     (fn [req]
       (let [doc-id  (get-in req [:path-params :id])
             user-id (get-in req [:session :user-id])
             doc     (documents/get-document documents doc-id)]
         (cond
           (nil? doc)                          (-> (resp/response "No such document")
                                                   (resp/status 404))
           (documents/can-access? doc user-id) (handler req)
           ;; the user this document is routed to may open it
           (= user-id (documents/assignee doc)) (handler req)
           (and access users
                (access/team-member? access (users/get-user users user-id) (:form-id doc)))
           (handler req)
           :else (forbidden "You don't have access to this document.")))))))

(defn wrap-require-datastar
  "reitit middleware: require the Datastar-Request header on state-changing
   (non-GET) requests — a header-based CSRF guard for the datastar endpoints."
  [handler]
  (fn [req]
    (if (or (= :get (:request-method req))
            (get-in req [:headers "datastar-request"]))
      (handler req)
      (forbidden "Missing Datastar-Request header."))))

;; --- CSRF tokens for HTML forms -------------------------------------------

(defn csrf-token
  "The current request's anti-forgery token (valid only inside wrap-anti-forgery)."
  []
  (force af/*anti-forgery-token*))

(defn csrf-field
  "Hidden input carrying the anti-forgery token, for plain HTML form POSTs."
  []
  [:input {:type "hidden" :name "__anti-forgery-token" :value (csrf-token)}])

(def wrap-anti-forgery af/wrap-anti-forgery)
