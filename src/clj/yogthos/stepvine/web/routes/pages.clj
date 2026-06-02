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
   [yogthos.stepvine.web.auth :as auth]
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

(defn page-routes [{:keys [forms documents session hub options-store patient-client users audit reports-dir]}]
  (let [resources  {:forms           forms
                    :documents       documents
                    :session-manager session
                    :hub             hub
                    :options-store   options-store
                    :patient-client  patient-client
                    :users           users
                    :audit           audit
                    :reports-dir     reports-dir}
        page (fn [wf] {:get  {:handler (mw/workflow-handler wf {:resources resources})}})
        post (fn [wf] {:post {:handler (mw/workflow-handler
                                        wf {:resources resources
                                            :output-fn mw/ring-response})}})
        af   #(assoc % :middleware [security/wrap-anti-forgery])      ; HTML form CSRF
        ds   #(assoc % :middleware [security/wrap-require-datastar])  ; datastar CSRF
        doc-access (security/wrap-doc-access documents)]
    [;; public auth routes (anti-forgery tokens)
     ["/login"    (af {:get auth/login-get    :post (auth/login-post users)})]
     ["/register" (af {:get auth/register-get :post (auth/register-post users)})]
     ["/logout"   (af {:post auth/logout})]
     ;; landing + create (anti-forgery)
     ["/"             (af (page doc/index))]
     ["/form/:id/new" (af (post doc/create))]
     ;; document routes — access-controlled
     ["/doc/:id" {:middleware [doc-access]}
      [""        (page doc/render-doc)]
      ["/sse"    {:get {:handler (sse/make-handler {:hub hub :manager session
                                                    :forms forms :documents documents})}}]
      ["/share"  (af (post doc/share))]
      ["/delete" (af (post doc/delete))]
      ["/submit" (ds (post doc/submit))]
      ["/revise" (ds (post doc/revise))]
      ["/wf/:action" (ds (post wf/run-action))]
      ["/report/:idx" {:get {:handler (report-handler documents)}}]
      ["/field/:fid"         (ds (post form/update-field))]
      ["/field/:fid/lock"    (ds (post form/lock-field))]
      ["/field/:fid/unlock"  (ds (post form/unlock-field))]
      ["/coll/:coll/add"             (ds (post form/coll-add))]
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
      ["/build"              (ds (post doc/build))]]]))

(derive :reitit.routes/pages :reitit/routes)

(defmethod ig/init-key :reitit.routes/pages
  [_ opts]
  (fn []
    ;; wrap-auth gates every page route; it lets /login and /register through
    ["" {:middleware [auth/wrap-auth]} (page-routes opts)]))
