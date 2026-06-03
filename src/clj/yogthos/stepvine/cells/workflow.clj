(ns yogthos.stepvine.cells.workflow
  "Mycelium cells for the document workflow state machine (PLAN.md §15.10–15.12).

   These cells are the *states* of a small mycelium FSM (see
   `yogthos.stepvine.workflows.workflow`): load → guard → (branch) → effects →
   commit / reject. The branch is a real mycelium `:dispatches` guard over the
   data (`:permitted?`), so the action-execution machine — and the document state
   transition it performs — is expressed in mycelium, not a hand-rolled loop."
  (:require
   [jsonista.core :as json]
   [mycelium.core :as myc]
   [starfederation.datastar.clojure.api :as d*]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.directives :as directives]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.workflow :as workflow]))

(defn posted-rev
  "The optimistic-concurrency `rev` signal a datastar POST carried, or nil. The
   client seeds + keeps `$rev` current over SSE; an action commits a decision, so
   it sends the rev it acted on."
  [req]
  (try (some-> (json/read-value (d*/get-signals req)) (get "rev") long)
       (catch Exception _ nil)))

(myc/defcell :wf/parse
  {:doc "Parse the workflow-action request: doc id (path), action (path), user, rev."}
  (fn [_ {req :http-request}]
    {:doc-id (get-in req [:path-params :id])
     :action (keyword (get-in req [:path-params :action]))
     :uid    (get-in req [:session :user-id])
     :rev    (posted-rev req)}))

(myc/defcell :wf/load
  {:doc      "Load the document, its form workflow, current state, value/reaction
              context, and which other editors are present."
   :requires [:forms :documents :session-manager :hub :users]}
  (fn [{:keys [session-manager hub documents users] :as resources} {:keys [doc-id uid] :as data}]
    (if-let [{:keys [form-raw]} (docs/ensure! resources doc-id)]
      (let [wf    (:workflow form-raw)
            doc   (documents/get-document documents doc-id)
            state (documents/workflow-state doc (workflow/initial-state wf))
            rctx  (render/session->context (session/current session-manager doc-id) :default doc-id)]
        (assoc data
               :found?   (boolean wf)
               :form     form-raw
               :workflow wf
               :state    state
               :doc-rev  (:rev doc)
               :ctx      {:rxns  (:rxns rctx) :doc (:values rctx)
                          :roles (users/roles (users/get-user users uid))}   ; actor's roles
               :others   (disj (hub/users hub doc-id) uid)))
      (assoc data :found? false))))

(myc/defcell :wf/guard
  {:doc "Decide whether the action is permitted: known workflow, legal transition,
         sole editor, validity guard, and role. Sets :permitted? for the branch."}
  (fn [_ {:keys [found? workflow state action ctx others rev doc-rev] :as data}]
    (let [reason (cond
                   (not found?)                                       :no-workflow
                   (not (workflow/permitted? workflow state action))  :illegal-transition
                   ;; optimistic concurrency: the client acted on a rev older than
                   ;; the document's current one (it hadn't yet seen a change)
                   (and rev (not= rev (or doc-rev 0)))                :stale
                   (seq others)                                       :other-editors
                   (not (workflow/guard-ok? workflow action ctx))     :invalid
                   (not (workflow/role-ok? workflow action ctx))      :forbidden
                   :else                                              nil)]
      (assoc data :permitted? (nil? reason) :reason reason))))

(myc/defcell :wf/effects
  {:doc "Compute the directive list for the permitted transition (pure)."}
  (fn [_ {:keys [workflow state action ctx] :as data}]
    (assoc data :directives (workflow/action-directives workflow state action ctx))))

(myc/defcell :wf/commit
  {:doc      "Apply the directives (transition + steps), audit the action, ack 204."
   :requires [:documents :session-manager :hub :audit :mailer :reports-dir :http-client]}
  (fn [{:keys [audit] :as resources} {:keys [form doc-id uid action directives]}]
    (directives/apply! resources form doc-id uid directives)
    (audit/record! audit {:doc-id doc-id :by uid :action :wf/action :detail {:action action}})
    {:status 204 :body ""}))

(def ^:private reject-message
  {:illegal-transition "That action isn't allowed from the current state."
   :stale              "This document changed elsewhere — refresh and try again."
   :other-editors      "Can't run this while others are editing the document."
   :invalid            "Can't proceed — please fix the highlighted fields."
   :forbidden          "You don't have permission to perform this action."
   :no-workflow        "This form has no workflow."})

(myc/defcell :wf/reject
  {:doc      "Surface the guard failure to the client and respond 409."
   :requires [:hub]}
  (fn [{:keys [hub]} {:keys [doc-id reason]}]
    (hub/broadcast-signals! hub doc-id {"notice" (reject-message reason (name reason))})
    {:status 409 :body (name reason)}))
