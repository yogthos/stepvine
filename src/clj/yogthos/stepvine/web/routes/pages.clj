(ns yogthos.stepvine.web.routes.pages
  "Server-rendered page + reactive routes, with auth, per-document access control
   and CSRF.

   Middleware layering (outer → inner):
     wrap-auth        every page route (redirects anonymous users to /login)
     wrap-doc-access  every /doc/:id route (owner or shared user only)
     CSRF, one of:
       wrap-anti-forgery     HTML form POSTs (login/register/logout/create/share/delete)
       wrap-require-datastar Datastar JSON endpoints (field/lock/coll/action)

   Datastar sets `Datastar-Request: true` automatically, so its endpoints need no
   token plumbing; plain HTML forms carry an anti-forgery token."
  (:require
   [clojure.java.io :as io]
   [integrant.core :as ig]
   [mycelium.middleware :as mw]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.web.admin :as admin]
   [yogthos.stepvine.web.auth :as auth]
   [yogthos.stepvine.web.editor :as editor]
   [yogthos.stepvine.web.oauth :as oauth]
   [yogthos.stepvine.web.pagenav :as pagenav]
   [yogthos.stepvine.web.collection-entry :as collection-entry]
   [yogthos.stepvine.web.collection-item :as collection-item]
   [yogthos.stepvine.web.doc-search :as doc-search]
   [yogthos.stepvine.web.queue :as queue]
   [yogthos.stepvine.web.report :as report]
   [yogthos.stepvine.web.search :as search]
   [yogthos.stepvine.web.security :as security]
   [yogthos.stepvine.web.sse :as sse]
   [yogthos.stepvine.workflows.document :as doc]
   [yogthos.stepvine.workflows.form :as form]
   [yogthos.stepvine.workflows.workflow :as wf]))

(defn- report-handler
  "Serve a generated PDF report (under the doc-access middleware) for download."
  [documents]
  (fn [req]
    (let [id   (get-in req [:path-params :id])
          idx  (parse-long (get-in req [:path-params :idx]))
          path (get-in (documents/get-document documents id) [:meta :reports idx :pdf])]
      (if (and path (.exists (io/file path)))
        {:status  200
         :headers {"Content-Type"        "application/pdf"
                   "Content-Disposition" (str "inline; filename=report-" idx ".pdf")}
         :body    (io/file path)}
        {:status 404 :body "No such report."}))))

(defn- app-style-handler
  "Serve an app's own CSS live from the store (re-skin without redeploy)."
  [forms-store]
  (fn [req]
    (if-let [css (forms/css forms-store (get-in req [:path-params :id]))]
      {:status 200 :headers {"Content-Type" "text/css" "Cache-Control" "no-cache"} :body css}
      {:status 404 :headers {"Content-Type" "text/css"} :body "/* no app css */"})))

(defn page-routes [{:keys [forms documents session hub options-store patient-client users audit reports-dir oauth access mailer http-client]}]
  (let [resources  {:forms           forms
                    :documents       documents
                    :session-manager session
                    :hub             hub
                    :options-store   options-store
                    :patient-client  patient-client
                    :users           users
                    :audit           audit
                    :access          access
                    :mailer          mailer
                    :http-client     http-client
                    :reports-dir     reports-dir}
        page (fn [wf] {:get  {:handler (mw/workflow-handler wf {:resources resources})}})
        post (fn [wf] {:post {:handler (mw/workflow-handler
                                        wf {:resources resources
                                            :output-fn mw/ring-response})}})
        af   #(assoc % :middleware [security/wrap-anti-forgery])      ; HTML form CSRF
        ds   #(assoc % :middleware [security/wrap-require-datastar])  ; datastar CSRF
        doc-access (security/wrap-doc-access documents {:users users :access access})]
    [;; public auth routes (anti-forgery tokens)
     ["/login"    (af {:get  (auth/login-get (oauth/provider-list (:providers oauth)))
                       :post (auth/login-post users (oauth/provider-list (:providers oauth)))})]
     ["/register" (af {:get auth/register-get :post (auth/register-post users)})]
     ["/logout"   (af {:post auth/logout})]
     ;; OAuth2 / OIDC flow (§15.13) — pre-login, no anti-forgery (GET redirects)
     ["/oauth/:provider"          {:get {:handler (oauth/start (:providers oauth))}}]
     ["/oauth/:provider/callback" {:get {:handler (oauth/callback (:providers oauth) users
                                                                  {:exchange-fn (:exchange-fn oauth)})}}]
     ;; ── protected group: every route below requires a valid session ──────
     ;; wrap-auth (on the group) resolves the session user — a missing/stale one
     ;; is bounced to /login; the /doc subtree adds per-document access on top.
     ["" {:middleware [(partial auth/wrap-auth users)]}
     ;; landing + create (anti-forgery)
     ["/"             (af (page doc/index))]
     ["/form/:id/new" (af (merge (page doc/new-page) (post doc/create)))]
     ;; content search across the documents the signed-in user can access (§j00)
     ["/search"       {:get {:handler (doc-search/handler resources)}}]
     ;; work queues — a workflowed form's documents by state, for its team
     ["/queue"        (af {:get (queue/index documents forms users access)})]
     ["/queue/:form"  (af {:get (queue/for-form documents forms users access)})]
     ["/queue/:form/:id/claim" (af {:post (queue/claim documents forms users access)})]
     ;; an app's own CSS, served live from the store (app-owned styling)
     ["/app/:id/style.css" {:get {:handler (app-style-handler forms)}}]
     ;; admin UI — role assignment (admin-only)
     ["/admin" {:middleware [(partial admin/wrap-admin users)]}
      ["/users"              (af {:get  (admin/users-page users)})]
      ["/users/new"          (af {:post (admin/create-user users)})]
      ["/users/:id/roles"    (af {:post (admin/set-user-roles users)})]
      ["/users/:id/password" (af {:post (admin/set-user-password users)})]
      ["/users/:id/delete"   (af {:post (admin/delete-user users)})]
      ["/forms"              (af {:get  (admin/forms-page forms access users)})]
      ["/forms/new"          (af {:post (editor/create-form forms)})]
      ["/forms/preview"      (ds {:post (editor/preview-handler forms options-store)})]
      ["/forms/:id/roles"    (af {:post (admin/set-form-access access)})]
      ["/forms/:id/edit"     (af {:get  (editor/edit-page forms access users)})]
      ["/forms/:id/save"     (ds {:post (editor/save forms)})]
      ["/forms/:id/publish"  (ds {:post (editor/publish forms)})]   ; promote draft -> version
      ["/forms/:id/discard"  (ds {:post (editor/discard forms)})]   ; drop the draft
      ["/outbox"             (af {:get  (admin/outbox-page mailer http-client users)})]]
     ;; document routes — access-controlled
     ["/doc/:id" {:middleware [doc-access]}
      [""        (page doc/render-doc)]
      ["/sse"    {:get {:handler (sse/make-handler {:hub hub :manager session
                                                    :forms forms :documents documents})}}]
      ;; in-place page switch (multi-page forms): morph the view region, no reload
      ["/page/:vid" (ds {:post {:handler (pagenav/switch-handler resources)}})]
      ;; server-side typeahead: query a source, morph in only the matches
      ["/search/:fid" (ds {:post {:handler (search/handler resources)}})]
      ["/share"  (af (post doc/share))]
      ["/delete" (af (post doc/delete))]
      ["/submit" (ds (post doc/submit))]
      ["/revise" (ds (post doc/revise))]
      ["/wf/:action" (ds (post wf/run-action))]
      ["/report/:idx" {:get {:handler (report-handler documents)}}]
      ;; in-browser HTML/Markdown report (declared :reports), not the PDF
      ["/reports/:rid" {:get {:handler (report/handler resources)}}]
      ["/field/:fid"         (ds (post form/update-field))]
      ["/field/:fid/lock"    (ds (post form/lock-field))]
      ["/field/:fid/unlock"  (ds (post form/unlock-field))]
      ["/coll/:coll/add"             (ds (post form/coll-add))]
      ;; modal data-entry: commit scratch/temp fields as a new row (§ugx)
      ["/coll/:coll/add-from" (ds {:post {:handler (collection-entry/handler resources)}})]
      ;; nested collections (§jj9): deep-path item edits via ?path=coll/idx/coll…
      ["/citem/field/:fid"        (ds {:post {:handler (collection-item/handler resources :field)}})]
      ["/citem/field/:fid/lock"   (ds {:post {:handler (collection-item/handler resources :noop)}})]
      ["/citem/field/:fid/unlock" (ds {:post {:handler (collection-item/handler resources :noop)}})]
      ["/citem/add"               (ds {:post {:handler (collection-item/handler resources :add)}})]
      ["/citem/remove"            (ds {:post {:handler (collection-item/handler resources :remove)}})]
      ["/coll/:coll/:idx/remove"     (ds (post form/coll-remove))]
      ["/coll/:coll/:idx/field/:fid"        (ds (post form/coll-field))]
      ["/coll/:coll/:idx/field/:fid/lock"   (ds (post form/coll-lock))]
      ["/coll/:coll/:idx/field/:fid/unlock" (ds (post form/coll-unlock))]
      ;; table operations
      ["/coll/:coll/sort"            (ds (post form/coll-sort))]
      ["/coll/:coll/page"            (ds (post form/coll-page))]
      ["/coll/:coll/filter"          (ds (post form/coll-filter))]
      ["/coll/:coll/move-row"        (ds (post form/coll-move-row))]
      ["/coll/:coll/clear"           (ds (post form/coll-clear))]
      ["/coll/:coll/columns-add"     (ds (post form/coll-columns-add))]
      ["/coll/:coll/columns-move"    (ds (post form/coll-columns-move))]
      ["/coll/:coll/columns-remove"  (ds (post form/coll-columns-remove))]
      ["/coll/:coll/columns-label"   (ds (post form/coll-columns-label))]
      ["/action/:aid"        (ds (post doc/run-export))]
      ["/build"              (ds (post doc/build))]]]]))

(derive :reitit.routes/pages :reitit/routes)

(defmethod ig/init-key :reitit.routes/pages
  [_ opts]
  (fn []
    ;; a single route node (empty path, no group-level middleware): page-routes
    ;; splits public (login/register/oauth) from a protected sub-group whose
    ;; wrap-auth requires a valid session.
    ["" {} (page-routes opts)]))
