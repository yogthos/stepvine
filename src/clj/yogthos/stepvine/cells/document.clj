(ns yogthos.stepvine.cells.document
  "Mycelium cells for the document lifecycle — Phase 5.

     GET  /                  landing: list documents + create from templates
     POST /form/:id/new      create a new document instance -> redirect to it
     GET  /doc/:id[?view=]   render a document instance (any of its views)
     POST /doc/:id/action/:aid  run a templated export, broadcast the result"
  (:require
   [hiccup2.core :as h]
   [jsonista.core :as json]
   [mycelium.core :as myc]
   [selmer.parser :as selmer]
   [starfederation.datastar.clojure.api :as d*]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.builder :as builder]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.exports :as exports]
   [yogthos.stepvine.index :as index]
   [yogthos.stepvine.imports :as imports]
   [yogthos.stepvine.forms-compile :as forms-compile]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.landing :as landing]
   [yogthos.stepvine.web.layout :as layout]
   yogthos.stepvine.components)  ; register all widget render methods
  (:import
   [java.util Date]))

(def ^:private pretty (json/object-mapper {:pretty true}))

;; --- GET / (landing) ------------------------------------------------------
;; The landing + index-lookup hiccup views live in `web.landing`; this cell
;; orchestrates (resolve accessible forms + their documents) and delegates.

(myc/defcell :index/render
  {:requires [:documents :forms :users :access]
   :input    {:http-request :map}
   :output   {:html :string}
   :doc      "Render the landing: the forms the signed-in user can access (by role),
              each with its documents (optionally filtered by ?status)."}
  (fn [{:keys [documents forms users access]} {req :http-request}]
    (let [user-id    (get-in req [:session :user-id])
          user       (users/get-user users user-id)
          status     (some-> (get-in req [:query-params "status"]) keyword)
          form-ids   (access/accessible-forms access user (forms/list-forms forms))
          form-list  (map (fn [id] (let [f (forms-compile/get-form forms id)]
                                     {:id id :title (:title f) :index? (boolean (:index f))}))
                          form-ids)
          docs-by    (group-by :form-id (documents/accessible-by documents user-id))]
      {:html (landing/landing-html {:forms form-list :docs-by-form docs-by :user user
                            :users users :admin? (users/admin? user) :status status})})))

(myc/defcell :doc/new-page
  {:requires [:forms :users]
   :input    {:http-request :map}
   :output   {:html :string}
   :doc      "GET /form/:id/new — the index lookup page for an index form."}
  (fn [{:keys [forms users]} {req :http-request}]
    (let [form-id (get-in req [:path-params :id])
          user    (users/get-user users (get-in req [:session :user-id]))]
      {:html (landing/index-page-html user form-id (forms-compile/get-form forms form-id) nil nil)})))

;; --- POST /form/:id/new (create) ------------------------------------------

(myc/defcell :doc/parse-create
  {:input {:http-request :map} :output {:form-id :any :user-id :any :index-key :any}
   :doc   "Form id (path), creating user (session), optional index key (form param)."}
  (fn [_ {req :http-request}]
    {:form-id   (get-in req [:path-params :id])
     :user-id   (get-in req [:session :user-id])
     :index-key (get-in req [:params :index-key])}))

(defn- create-pinned! [documents forms form-id user-id]
  (let [version (forms/latest-published forms form-id)]
    (documents/create! documents form-id
                       {:created-by   user-id
                        :form-version version
                        :form-digest  (forms/version-digest forms form-id version)})))

(defn- do-create
  [{:keys [documents forms users] :as resources} form-id user-id index-key]
  (let [form     (forms-compile/get-form forms form-id)
        idx-spec (:index form)
        user     (users/get-user users user-id)]
    (cond
      ;; index form with a key: validate, create, seed, prepopulate
      (and idx-spec (seq (str index-key)))
      (if-not (:found? (index/lookup (imports/source-ctx resources) idx-spec index-key))
        {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (landing/index-page-html user form-id form index-key "No match for that key — please check it.")}
        (let [doc (create-pinned! documents forms form-id user-id)
              id  (:id doc)
              into (:into idx-spec)]
          (docs/ensure! resources id)
          (docs/hydrate! resources form id user)                  ; created-by / today / defaults
          (session/apply-change! (:session-manager resources) id [[into index-key]])  ; seed trigger field
          (docs/run-imports! resources form id into)               ; imports map the entity
          {:status 303 :headers {"Location" (str "/doc/" id)} :body ""}))

      ;; index form, no key yet: (re)show the lookup page
      idx-spec
      {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (landing/index-page-html user form-id form nil nil)}

      ;; plain form: create, hydrate creation-time fields, and go
      :else
      (let [id (:id (create-pinned! documents forms form-id user-id))]
        (docs/ensure! resources id)
        (docs/hydrate! resources form id user)                    ; created-by / today / defaults
        {:status 303 :headers {"Location" (str "/doc/" id)} :body ""}))))

(myc/defcell :doc/create
  {:requires [:documents :forms :session-manager :patient-client :options-store :access :users]
   :input    {:form-id :any :user-id :any :index-key :any}
   :output   {:status :int :headers :any :body :string}
   :doc      "Create a document (pinned), if the user may access the form. For an
              index form, resolve the key, then seed the :into field so imports
              prepopulate it (§15.13)."}
  (fn [{:keys [access users] :as resources} {:keys [form-id user-id index-key]}]
    (if-not (access/can-access? access (users/get-user users user-id) form-id)
      {:status 303 :headers {"Location" "/"} :body ""}      ; no access — back to landing
      (do-create resources form-id user-id index-key))))

;; --- GET /doc/:id (render an instance) ------------------------------------

(myc/defcell :doc/parse
  {:input {:http-request :map} :output {:doc-id :any :view-id :any :user-id :any}
   :doc   "Document id (path), optional ?view, and the signed-in user."}
  (fn [_ {req :http-request}]
    {:doc-id  (get-in req [:path-params :id])
     :view-id (or (get-in req [:query-params "view"]) "default")
     :user-id (get-in req [:session :user-id])}))

(defn render-doc-view
  "Ensure a document's session and render ONE of its views to an HTML string,
   with the full context (lock owner, read-only when finalized, workflow state,
   resolved option sources). Resolves the requested view, falling back to the
   first page of a multi-page form (or :default). Returns
   `{:vid :html :form-raw :pages :doc}` or nil when the document doesn't exist.
   Shared by the full-page render and the in-place page switch."
  [{:keys [session-manager options-store documents users] :as resources} doc-id view-id user-id]
  (when-let [{:keys [form-raw]} (docs/ensure! resources doc-id)]
    (let [sess     (session/current session-manager doc-id)
          user     (users/get-user users user-id)
          views    (get-in sess [:form :views])
          doc      (documents/get-document documents doc-id)
          wstate   (when-let [wf (:workflow form-raw)] (documents/workflow-state doc (:initial wf)))
          ;; per-view gate (§parity): a view's :roles restrict who may see it
          view-ok? (fn [v] (access/role-permitted? user (get-in views [(keyword v) :roles])))
          ;; only the pages this user may access appear in the nav
          pages    (filterv #(view-ok? (:view %)) (:pages form-raw))
          vid  (let [v (keyword view-id)
                     ;; with no explicit view (:default), auto-select a view whose
                     ;; :for-states includes the current workflow state, role-permitted
                     auto (when (= v :default)
                            (first (keep (fn [[k vw]]
                                           (when (and (view-ok? k) (contains? (set (:for-states vw)) wstate))
                                             k))
                                         views)))]
                 (cond
                   auto auto                                         ; state-selected view
                   (and (get views v) (view-ok? v)) v               ; requested/default, if it exists + permitted
                   (seq pages)        (keyword (:view (first pages))) ; first permitted page
                   :else (first (keep (fn [[k _]] (when (view-ok? k) k)) views))))  ; first permitted view
          ctx  (-> (render/session->context sess vid doc-id)
                   (assoc :uid user-id)   ; the authenticated user drives lock comparison
                   ;; granular field permissions (§parity): hide :read-roles fields
                   ;; and force :write-roles fields read-only for this user
                   (assoc :perm-roles (users/roles user) :perm-admin? (users/admin? user))
                   ;; finalized documents render read-only (§15.5)
                   (assoc :locked? (documents/locked? doc))
                   ;; optimistic-concurrency token seeded into $rev (kept current
                   ;; over SSE); consequential actions post it back to detect staleness
                   (assoc :rev (:rev doc))
                   ;; current workflow state — drives $state action buttons (§15.10)
                   ;; AND :writable-in field editability (§parity)
                   (assoc :workflow-state wstate)
                   ;; resolve option sources for top-level AND collection-item fields
                   (assoc :options (options/resolve-field-options options-store (signals/all-field-opts sess))))]
      {:vid      vid
       :html     (render/render-view ctx (render/view-markup sess vid))
       :form-raw form-raw
       :pages    pages               ; permitted pages only → page nav
       :doc      doc
       :theme    (render/theme-href sess vid)})))

(myc/defcell :doc/render
  {:requires [:forms :documents :session-manager :options-store :users]
   :input    {:doc-id :any :view-id :any :user-id :any}
   :output   {:html :string}
   :doc      "Ensure the document's session and render the requested view, inside
              the shared navbar/footer chrome with breadcrumbs."}
  (fn [{:keys [users] :as resources} {:keys [doc-id view-id user-id]}]
    (if-let [{:keys [vid html form-raw pages doc theme]}
             (render-doc-view resources doc-id view-id user-id)]
      (let [user   (users/get-user users user-id)
            crumbs [{:label "Documents" :href "/"} {:label (or (:title form-raw) (name (:form-id doc)))}]]
        {:html (selmer/render-file "html/form.html"
                                   {:title     (:title form-raw)
                                    :view      html
                                    :theme     theme
                                    :app_css   (forms/app-css-href (:forms resources) (:form-id doc))  ; live app styling
                                    :navbar    (layout/navbar-html user crumbs)
                                    :page_tabs (layout/page-tabs-html pages vid doc-id)
                                    :page_nav  (layout/page-prevnext-html pages vid doc-id)
                                    :multipage (boolean (seq pages))
                                    :footer    (layout/footer-html)})})
      {:html (selmer/render-file "html/form.html"
                                 {:title "Not found" :view "<p>No such document.</p>"})})))

;; --- POST /doc/:id/action/:aid (export) -----------------------------------

(myc/defcell :doc/parse-action
  {:input {:http-request :map} :output {:doc-id :any :action-id :any}
   :doc   "Document + action ids from the path."}
  (fn [_ {req :http-request}]
    {:doc-id    (get-in req [:path-params :id])
     :action-id (get-in req [:path-params :aid])}))

(myc/defcell :doc/run-export
  {:requires [:forms :documents :session-manager :hub]
   :input    {:doc-id :any :action-id :any}
   :output   {:status :int :body :string}
   :doc      "Run a templated export and broadcast the result into #export-result."}
  (fn [{:keys [session-manager hub] :as resources} {:keys [doc-id action-id]}]
    (when (docs/ensure! resources doc-id)
      (let [sess   (session/current session-manager doc-id)
            result (exports/render-export sess action-id)
            json   (json/write-value-as-string result pretty)
            html   (str (h/html [:pre {:id "export-result"} json]))]
        (hub/broadcast-elements! hub doc-id html)))
    {:status 204 :body ""}))

;; --- POST /doc/:id/submit | /revise (§15.5) -------------------------------

(myc/defcell :doc/parse-view-op
  {:input  {:http-request :map}
   :output {:doc-id :any :view-id :any :uid :any :rev :any}
   :doc    "Document id (path), target view (query, default :default), acting user, rev."}
  (fn [_ {req :http-request}]
    {:doc-id  (get-in req [:path-params :id])
     :view-id (keyword (or (get-in req [:query-params "view"]) "default"))
     :uid     (get-in req [:session :user-id])
     :rev     (try (some-> (json/read-value (d*/get-signals req)) (get "rev") long)
                   (catch Exception _ nil))}))

(defn- broadcast-lock! [hub doc-id locked?]
  (hub/broadcast-signals! hub doc-id {"locked" locked?}))

(defn- notice! [hub doc-id msg]
  ;; a transient message surfaced by the form's status region
  (hub/broadcast-signals! hub doc-id {"notice" msg}))

(myc/defcell :doc/submit
  {:requires [:forms :documents :session-manager :hub :audit]
   :input    {:doc-id :any :view-id :any :uid :any :rev :any}
   :output   {:status :int :body :string}
   :doc      "Finalize a view: guard rev (optimistic concurrency) + sole-editor +
              validity, snapshot, approve, lock."}
  (fn [{:keys [session-manager hub documents audit] :as resources} {:keys [doc-id view-id uid rev]}]
    (if-let [{:keys [form-raw]} (docs/ensure! resources doc-id)]
      (let [doc         (documents/get-document documents doc-id)
            others      (disj (hub/users hub doc-id) uid)
            submit-when (get-in form-raw [:views view-id :opts :submit-when])
            valid?      (or (nil? submit-when)
                            (boolean (session/value session-manager doc-id submit-when)))]
        (cond
          ;; optimistic concurrency: the user submitted from a stale view (§j00/oc)
          (not (documents/rev-current? documents doc-id rev))
          (do (notice! hub doc-id "This document changed elsewhere — refresh and try again.")
              {:status 409 :body "Stale document revision."})

          (documents/submitted-for? doc view-id)
          {:status 409 :body "Already submitted."}

          (seq others)                                  ; sole-editor guard
          (do (notice! hub doc-id "Can't submit while others are editing this document.")
              {:status 409 :body "Other editors present."})

          (not valid?)                                  ; validity gate
          (do (notice! hub doc-id "Can't submit — please fix the highlighted fields.")
              {:status 409 :body "Document is not valid."})

          :else
          (let [snapshot (impl/db (session/current session-manager doc-id))]
            (documents/submit! documents doc-id view-id uid snapshot)
            (audit/record! audit {:doc-id doc-id :by uid :action :doc/submit
                                  :detail {:view view-id}})
            (broadcast-lock! hub doc-id true)
            {:status 204 :body ""})))
      {:status 404 :body (str "No such document: " doc-id)})))

(myc/defcell :doc/revise
  {:requires [:forms :documents :session-manager :hub :audit]
   :input    {:doc-id :any :view-id :any :uid :any :rev :any}
   :output   {:status :int :body :string}
   :doc      "Re-open a submitted view (rev-guarded); broadcast the unlocked state."}
  (fn [{:keys [hub documents audit] :as resources} {:keys [doc-id view-id uid rev]}]
    (cond
      (not (docs/ensure! resources doc-id))
      {:status 404 :body "No such document."}

      (not (documents/rev-current? documents doc-id rev))
      (do (notice! hub doc-id "This document changed elsewhere — refresh and try again.")
          {:status 409 :body "Stale document revision."})

      :else
      (do (documents/revise! documents doc-id view-id)
          (audit/record! audit {:doc-id doc-id :by uid :action :doc/revise
                                :detail {:view view-id}})
          (broadcast-lock! hub doc-id
                           (documents/locked? (documents/get-document documents doc-id)))
          {:status 204 :body ""}))))

;; --- POST /doc/:id/share | /delete (owner-only) ---------------------------

(myc/defcell :doc/parse-owner-op
  {:input {:http-request :map} :output {:doc-id :any :user-id :any :username :any}
   :doc   "Document id (path), acting user (session), target username (form param)."}
  (fn [_ {req :http-request}]
    {:doc-id   (get-in req [:path-params :id])
     :user-id  (get-in req [:session :user-id])
     :username (get-in req [:params :username])}))

(myc/defcell :doc/share
  {:requires [:documents :users]
   :input    {:doc-id :any :user-id :any :username :any}
   :output   {:status :int :headers :any :body :string}
   :doc      "Owner shares a document with another user (by username)."}
  (fn [{:keys [documents users]} {:keys [doc-id user-id username]}]
    (let [doc (documents/get-document documents doc-id)]
      (when (and doc (documents/owner? doc user-id))
        (when-let [target (users/find-by-username users username)]
          (documents/share! documents doc-id (:id target)))))
    {:status 303 :headers {"Location" "/"} :body ""}))

(myc/defcell :doc/delete
  {:requires [:documents]
   :input    {:doc-id :any :user-id :any :username :any}
   :output   {:status :int :headers :any :body :string}
   :doc      "Owner deletes a document."}
  (fn [{:keys [documents]} {:keys [doc-id user-id]}]
    (let [doc (documents/get-document documents doc-id)]
      (when (and doc (documents/owner? doc user-id))
        (documents/delete! documents doc-id)))
    {:status 303 :headers {"Location" "/"} :body ""}))

;; --- POST /doc/:id/build (form builder) -----------------------------------

(myc/defcell :doc/build
  {:requires [:forms :documents :session-manager :hub]
   :input    {:doc-id :any}
   :output   {:status :int :body :string}
   :doc      "Generate a form from a builder document and save it; broadcast result."}
  (fn [{:keys [forms session-manager hub] :as resources} {:keys [doc-id]}]
    (when (docs/ensure! resources doc-id)
      (let [sess (session/current session-manager doc-id)
            form (builder/build-form sess)
            html (if form
                   (let [id (forms/save-form! forms form)]
                     (str (h/html [:pre {:id "build-result"}
                                   (str "✓ Built form \"" (name id) "\". ")
                                   [:a {:href "/"} "Go home to create documents from it →"]])))
                   (str (h/html [:pre {:id "build-result"} "Enter a form id and at least one field first."])))]
        (hub/broadcast-elements! hub doc-id html)))
    {:status 204 :body ""}))
