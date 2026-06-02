(ns yogthos.stepvine.directives
  "In-process directive layer (PLAN.md §15.12).

   Workflow steps are pure — they return *directives*. `apply!` is the single place
   those become effects. It splits cleanly:

     • document mutations — `[:set-state s]`, `[:set-field path v]`, `[:set-meta
       path v]`, `[:assign uid]` — applied here against the session/document store
       (recompute, persist, audit, broadcast); and
     • side effects — `[:notify msg]`, `[:email …]`, `[:snapshot]`, `[:pdf …]` —
       routed to the unified host performer `effects/perform!`, the SAME executor
       the Domino engine's emitted effects use. So a workflow action and a
       data-change effect share one side-effect layer."
  (:require
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.effects :as effects]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.workflow :as workflow]))

(defn apply!
  "Apply `directives` for `doc-id` on behalf of `uid`, against `form` (whose
   `:workflow` governs transition locking, and whose fields render a `:pdf`).
   Returns the directives."
  [{:keys [session-manager documents hub audit] :as resources} form doc-id uid directives]
  (doseq [d directives]
    (case (first d)
      ;; --- document mutations ---
      :set-state (let [target  (second d)
                       locked? (workflow/locked-state? (:workflow form) target)]
                   (documents/set-workflow-state! documents doc-id target locked? uid)
                   (hub/broadcast-signals! hub doc-id {"locked" locked? "state" (name target)})
                   (audit/record! audit {:doc-id doc-id :by uid :action :state/transition
                                         :after target}))
      :set-field (session/apply-change! session-manager doc-id [[(nth d 1) (nth d 2)]])
      :set-meta  (documents/update-meta! documents doc-id (nth d 1) (nth d 2))
      :assign    (documents/assign! documents doc-id (second d) uid)
      ;; --- side effects -> the unified host performer ---
      :notify    (effects/perform! resources doc-id form {:kind :notify :message (second d)})
      :email     (effects/perform! resources doc-id form (assoc (second d) :kind :email))
      :snapshot  (effects/perform! resources doc-id form {:kind :snapshot :by uid})
      :pdf       (effects/perform! resources doc-id form (assoc (second d) :kind :pdf :by uid))
      nil))
  directives)
