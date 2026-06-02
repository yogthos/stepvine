(ns yogthos.stepvine.directives
  "In-process directive layer (PLAN.md §15.12).

   Workflow steps are pure — they return *directives* (`[:set-state s]`,
   `[:set-field path v]`, `[:set-meta path v]`, `[:notify msg]`, `[:snapshot]`).
   `apply!` is the single place those directives become effects: state
   transitions, field/meta writes, notifications and snapshots — recomputing,
   persisting, auditing and broadcasting like any other change.

   This is the reference's pure-step / applied-directive split, but in one
   process: where the reference returns hooks across a signed-HTTP service
   boundary, we apply them as ordinary in-process changes (no JWT handoff, no
   cross-service 409). Keeping steps pure makes them unit-testable with no I/O."
  (:require
   [clojure.java.io :as io]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.mailer :as mailer]
   [yogthos.stepvine.pdf :as pdf]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.workflow :as workflow]))

(defn- append-report! [documents doc-id entry]
  (let [cur (vec (get-in (documents/get-document documents doc-id) [:meta :reports]))]
    (documents/update-meta! documents doc-id [:reports] (conj cur entry))
    (count cur)))                         ; the new report's index

(defn- snapshot! [{:keys [session-manager documents]} doc-id uid]
  (let [db (impl/db (session/current session-manager doc-id))]
    (append-report! documents doc-id {:at (System/currentTimeMillis) :by uid :snapshot db})))

(defn- pdf! [{:keys [session-manager documents reports-dir]} form doc-id uid opts]
  (let [rctx (render/session->context (session/current session-manager doc-id) :default doc-id)
        spec (if (:template opts)
               (pdf/substitute (:template opts) (:values rctx) (:rxns rctx))
               (pdf/document->spec form (:values rctx) (:rxns rctx)))
        idx  (count (get-in (documents/get-document documents doc-id) [:meta :reports]))
        file (io/file (or reports-dir "data/reports") doc-id (str "report-" idx ".pdf"))]
    (pdf/write! spec file)
    (append-report! documents doc-id {:at (System/currentTimeMillis) :by uid
                                      :pdf (.getPath file) :idx idx})))

(defn apply!
  "Apply `directives` for `doc-id` on behalf of `uid`, against `form` (whose
   `:workflow` governs transition locking, and whose fields render a `:pdf`).
   Returns the directives."
  [{:keys [session-manager documents hub audit] :as resources} form doc-id uid directives]
  (doseq [d directives]
    (case (first d)
      :set-state (let [target  (second d)
                       locked? (workflow/locked-state? (:workflow form) target)]
                   (documents/set-workflow-state! documents doc-id target locked? uid)
                   (hub/broadcast-signals! hub doc-id {"locked" locked? "state" (name target)})
                   (audit/record! audit {:doc-id doc-id :by uid :action :state/transition
                                         :after target}))
      :set-field (session/apply-change! session-manager doc-id [[(nth d 1) (nth d 2)]])
      :set-meta  (documents/update-meta! documents doc-id (nth d 1) (nth d 2))
      :notify    (hub/broadcast-signals! hub doc-id {"notice" (second d)})
      :snapshot  (snapshot! resources doc-id uid)
      :pdf       (pdf! resources form doc-id uid (second d))
      :email     (mailer/send! (:mailer resources) (second d))
      :assign    (documents/assign! documents doc-id (second d) uid)
      nil))
  directives)
