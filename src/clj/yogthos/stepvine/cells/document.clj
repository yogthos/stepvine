(ns yogthos.stepvine.cells.document
  "Mycelium cells for the document lifecycle — Phase 5.

     GET  /                  landing: list documents + create from templates
     POST /form/:id/new      create a new document instance -> redirect to it
     GET  /doc/:id[?view=]   render a document instance (any of its views)
     POST /doc/:id/action/:aid  run a templated export, broadcast the result"
  (:require
   [clojure.string :as str]
   [hiccup2.core :as h]
   [jsonista.core :as json]
   [mycelium.core :as myc]
   [selmer.parser :as selmer]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.builder :as builder]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.exports :as exports]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.users :as users]
   yogthos.stepvine.components   ; register all widget render methods
   [yogthos.stepvine.web.security :as security])
  (:import
   [java.util Date]))

(def ^:private pretty (json/object-mapper {:pretty true}))

;; --- GET / (landing) ------------------------------------------------------

(defn- landing-html [docs form-list user users]
  (str "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title "Stepvine — Documents"]
          [:style (str "body{font-family:system-ui,sans-serif;max-width:44rem;margin:3rem auto;line-height:1.5}"
                       ".doc{padding:.5rem 0;border-bottom:1px solid #eee}"
                       "form{display:inline} small{color:#6b7280}"
                       ".bar{display:flex;justify-content:space-between;align-items:center}"
                       "input{padding:.2rem .4rem;border:1px solid #d1d5db;border-radius:.375rem}"
                       "button,a.btn{padding:.25rem .6rem;border:1px solid #d1d5db;border-radius:.375rem;"
                       "background:#fff;cursor:pointer;text-decoration:none;color:#111;margin-left:.3rem}")]]
         [:body
          [:div.bar
           [:h1 "Stepvine documents"]
           [:form {:method "post" :action "/logout"}
            (security/csrf-field)
            [:small "Signed in as " [:b (:display-name user)]]
            [:button "Sign out"]]]
          [:h2 "Create new"]
          [:p (for [{:keys [id title]} form-list]
                [:form {:method "post" :action (str "/form/" (name id) "/new")}
                 (security/csrf-field)
                 [:button (str "+ New " (or title (name id)))]])]
          [:h2 "Your documents"]
          (if (seq docs)
            (into [:div]
                  (for [{:keys [id form-id created-by shared]} docs]
                    (let [owner? (= (:id user) created-by)]
                      [:div.doc
                       [:a {:href (str "/doc/" id)} (str (name form-id))] " " [:small id]
                       (if owner? [:small " · owner"] [:small " · shared with you"])
                       (when owner?
                         [:span
                          [:form {:method "post" :action (str "/doc/" id "/share")}
                           (security/csrf-field)
                           [:input {:name "username" :placeholder "share with username"}]
                           [:button "Share"]]
                          (when (seq shared)
                            [:small " — shared with "
                             (str/join ", " (keep #(:username (users/get-user users %)) shared))])
                          [:form {:method "post" :action (str "/doc/" id "/delete")}
                           (security/csrf-field)
                           [:button "Delete"]]])])))
            [:p "No documents yet."])]])))

(myc/defcell :index/render
  {:requires [:documents :forms :users]
   :input    {:http-request :map}
   :output   {:html :string}
   :doc      "Render the documents landing for the signed-in user (their docs only)."}
  (fn [{:keys [documents forms users]} {req :http-request}]
    (let [user-id   (get-in req [:session :user-id])
          user      (users/get-user users user-id)
          form-list (map (fn [id] {:id id :title (:title (forms/get-form forms id))})
                         (forms/list-forms forms))]
      {:html (landing-html (documents/accessible-by documents user-id) form-list user users)})))

;; --- POST /form/:id/new (create) ------------------------------------------

(myc/defcell :doc/parse-create
  {:input {:http-request :map} :output {:form-id :any :user-id :any}
   :doc   "Form id to instantiate (from path) + creating user (from session)."}
  (fn [_ {req :http-request}]
    {:form-id (get-in req [:path-params :id])
     :user-id (get-in req [:session :user-id])}))

(myc/defcell :doc/create
  {:requires [:documents :forms]
   :input    {:form-id :any :user-id :any}
   :output   {:status :int :headers :any :body :string}
   :doc      "Create a document, pinning the latest published form version + digest."}
  (fn [{:keys [documents forms]} {:keys [form-id user-id]}]
    (let [version (forms/latest-published forms form-id)
          doc     (documents/create! documents form-id
                                     {:created-by   user-id
                                      :form-version version
                                      :form-digest  (forms/version-digest forms form-id version)})]
      {:status 303 :headers {"Location" (str "/doc/" (:id doc))} :body ""})))

;; --- GET /doc/:id (render an instance) ------------------------------------

(myc/defcell :doc/parse
  {:input {:http-request :map} :output {:doc-id :any :view-id :any :user-id :any}
   :doc   "Document id (path), optional ?view, and the signed-in user."}
  (fn [_ {req :http-request}]
    {:doc-id  (get-in req [:path-params :id])
     :view-id (or (get-in req [:query-params "view"]) "default")
     :user-id (get-in req [:session :user-id])}))

(myc/defcell :doc/render
  {:requires [:forms :documents :session-manager :options-store]
   :input    {:doc-id :any :view-id :any :user-id :any}
   :output   {:html :string}
   :doc      "Ensure the document's session and render the requested view."}
  (fn [{:keys [session-manager options-store documents] :as resources} {:keys [doc-id view-id user-id]}]
    (if-let [{:keys [form-raw]} (docs/ensure! resources doc-id)]
      (let [sess (session/current session-manager doc-id)
            vid  (let [v (keyword view-id)]
                   (if (get-in sess [:form :views v]) v :default))
            doc  (documents/get-document documents doc-id)
            ctx  (-> (render/session->context sess vid doc-id)
                     (assoc :uid user-id)   ; the authenticated user drives lock comparison
                     ;; finalized documents render read-only (§15.5)
                     (assoc :locked? (documents/locked? doc))
                     ;; current workflow state, for $state-driven action buttons (§15.10)
                     (assoc :workflow-state
                            (when-let [wf (:workflow form-raw)]
                              (documents/workflow-state doc (:initial wf))))
                     ;; resolve option sources for top-level AND collection-item fields
                     (assoc :options (options/resolve-field-options options-store (render/all-field-opts sess))))
            view (render/render-view ctx (render/view-markup sess vid))]
        {:html (selmer/render-file "html/form.html"
                                   {:title (:title form-raw)
                                    :view  view
                                    :theme (render/theme-href sess vid)})})
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
   :output {:doc-id :any :view-id :any :uid :any}
   :doc    "Document id (path), target view (query, default :default), acting user."}
  (fn [_ {req :http-request}]
    {:doc-id  (get-in req [:path-params :id])
     :view-id (keyword (or (get-in req [:query-params "view"]) "default"))
     :uid     (get-in req [:session :user-id])}))

(defn- broadcast-lock! [hub doc-id locked?]
  (hub/broadcast-signals! hub doc-id {"locked" locked?}))

(defn- notice! [hub doc-id msg]
  ;; a transient message surfaced by the form's status region
  (hub/broadcast-signals! hub doc-id {"notice" msg}))

(myc/defcell :doc/submit
  {:requires [:forms :documents :session-manager :hub :audit]
   :input    {:doc-id :any :view-id :any :uid :any}
   :output   {:status :int :body :string}
   :doc      "Finalize a view: guard sole-editor + validity, snapshot, approve, lock."}
  (fn [{:keys [session-manager hub documents audit] :as resources} {:keys [doc-id view-id uid]}]
    (if-let [{:keys [form-raw]} (docs/ensure! resources doc-id)]
      (let [doc         (documents/get-document documents doc-id)
            others      (disj (hub/users hub doc-id) uid)
            submit-when (get-in form-raw [:views view-id :opts :submit-when])
            valid?      (or (nil? submit-when)
                            (boolean (session/value session-manager doc-id submit-when)))]
        (cond
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
   :input    {:doc-id :any :view-id :any :uid :any}
   :output   {:status :int :body :string}
   :doc      "Re-open a submitted view; broadcast the unlocked state."}
  (fn [{:keys [hub documents audit] :as resources} {:keys [doc-id view-id uid]}]
    (when (docs/ensure! resources doc-id)
      (documents/revise! documents doc-id view-id)
      (audit/record! audit {:doc-id doc-id :by uid :action :doc/revise
                            :detail {:view view-id}})
      (broadcast-lock! hub doc-id
                       (documents/locked? (documents/get-document documents doc-id))))
    {:status 204 :body ""}))

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
