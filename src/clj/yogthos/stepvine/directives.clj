(ns yogthos.stepvine.directives
  "In-process directive layer (PLAN.md §15.12) with multi-step action resilience
   (§8gj).

   Workflow steps are pure — they return *directives*. `apply!` is the single place
   those become effects, run as a SAGA so a multi-step action is resilient:

     1. document data mutations — `[:set-field path v]`, `[:set-meta path v]`,
        `[:assign uid]` — applied first, so the side effects see updated data;
     2. side effects — `[:notify msg]`, `[:email …]`, `[:snapshot]`, `[:pdf …]`,
        `[:http …]` — run through `effects/run-saga!`: each at-most-once (skipped
        if already logged `:ok`), retried per `:retry`, compensated on failure;
     3. the state transition — `[:set-state s]` — committed ONLY when the saga
        succeeds. A failed external step leaves the document in its prior state,
        so the action can be safely retried (completed steps are skipped).

   Side effects route to `effects/perform!`, the SAME executor the Domino engine's
   emitted effects use — a workflow action and a data-change effect share one
   side-effect layer."
  (:require
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.effects :as effects]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.workflow :as workflow]))

(defn- side-effect-intent
  "The effect intent for a side-effect directive, or nil for a non-side-effect."
  [uid d]
  (case (first d)
    :notify   {:kind :notify :message (second d)}
    :email    (assoc (second d) :kind :email)
    :snapshot {:kind :snapshot :by uid}
    :pdf      (assoc (second d) :kind :pdf :by uid)
    :http     (assoc (second d) :kind :http)
    nil))

(defn- saga-steps
  "Compile the side-effect directives into saga steps with a stable idempotency
   key per (from-state, action, position) — so retrying the same (uncommitted)
   transition skips the steps that already succeeded."
  [from-state action uid directives]
  (->> directives
       (map-indexed
        (fn [i d]
          (when-let [intent (side-effect-intent uid d)]
            {:key        (str (name (or from-state :_)) "/" (name action) "/" i)
             :intent     intent
             ;; an optional declared compensation (a raw intent map: {:kind …})
             :compensate (:compensate (when (map? (second d)) (second d)))})))
       (remove nil?)
       vec))

(defn- commit-transition!
  [{:keys [documents hub audit]} form doc-id uid target]
  (let [locked? (workflow/locked-state? (:workflow form) target)]
    (documents/set-workflow-state! documents doc-id target locked? uid)
    (hub/broadcast-signals! hub doc-id {"locked" locked? "state" (name target)})
    (audit/record! audit {:doc-id doc-id :by uid :action :state/transition :after target})))

(defn apply!
  "Run an action's `directives` for `doc-id` on behalf of `uid` as a resilient
   saga (see ns doc). `action`/`from-state` key the idempotency log. Returns
   `{:status :ok|:failed :saga {…} :directives}`; on :failed the state transition
   was NOT committed."
  [{:keys [session-manager documents] :as resources} form doc-id uid action from-state directives]
  ;; 1. data mutations — applied before the effects so templates see fresh data
  (doseq [d directives]
    (case (first d)
      :set-field (session/apply-change! session-manager doc-id [[(nth d 1) (nth d 2)]])
      :set-meta  (documents/update-meta! documents doc-id (nth d 1) (nth d 2))
      :assign    (documents/assign! documents doc-id (second d) uid)
      nil))
  ;; 2. side effects as an idempotent, retrying, compensating saga
  (let [saga (effects/run-saga! resources doc-id form (saga-steps from-state action uid directives))]
    (if (= :ok (:status saga))
      ;; 3. commit the transition only once every effect has succeeded
      (do (doseq [d directives :when (= :set-state (first d))]
            (commit-transition! resources form doc-id uid (second d)))
          {:status :ok :saga saga :directives directives})
      {:status :failed :saga saga :directives directives})))