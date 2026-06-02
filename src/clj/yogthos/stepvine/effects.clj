(ns yogthos.stepvine.effects
  "The single host-side performer for side-effect *intents*. Two sources feed it:

     • the Domino engine, which emits intents (change-triggered) when a document's
       data moves — see `editor.data/*effect-sink*`; and
     • the workflow directive layer (`directives/apply!`), where an action's steps
       emit the same intents.

   Whatever the trigger, a side effect — send email, raise a notice, generate a
   pdf, snapshot the document, run an import — is performed here, where the host
   resources live (mailer, clients, hub, report dir). A new kind is one
   `defmethod`; neither the engine nor the workflow layer changes."
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.http :as http]
   [yogthos.stepvine.mailer :as mailer]
   [yogthos.stepvine.pdf :as pdf]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.sources :as sources]))

(defn- append-report!
  "Append a report entry (snapshot or pdf) to the document, returning its index."
  [documents doc-id entry]
  (let [cur (vec (get-in (documents/get-document documents doc-id) [:meta :reports]))]
    (documents/update-meta! documents doc-id [:reports] (conj cur entry))
    (count cur)))

(defmulti perform!
  "Perform one effect intent. Dispatch on `:kind`. Args: the cell resources, the
   document id, the (prepared) form, and the intent map. `:by` (when present) is
   the acting user, for intents emitted by a workflow action."
  (fn [_resources _doc-id _form intent] (:kind intent)))

(defmethod perform! :default [_ _ _ intent]
  (log/warn "unhandled effect intent" intent))

;; --- outbound / transient -------------------------------------------------

(defmethod perform! :email [{:keys [mailer]} _doc-id _form intent]
  (mailer/send! mailer (select-keys intent [:to :subject :body])))

(defmethod perform! :notify [{:keys [hub]} doc-id _form intent]
  (hub/broadcast-signals! hub doc-id {"notice" (str (:message intent))}))

;; --- document artifacts ---------------------------------------------------

(defmethod perform! :snapshot [{:keys [session-manager documents]} doc-id _form intent]
  (let [db (impl/db (session/current session-manager doc-id))]
    (append-report! documents doc-id
                    {:at (System/currentTimeMillis) :by (:by intent) :snapshot db})))

(defmethod perform! :pdf [{:keys [session-manager documents reports-dir]} doc-id form intent]
  (let [rctx (render/session->context (session/current session-manager doc-id) :default doc-id)
        spec (if (:template intent)
               (pdf/substitute (:template intent) (:values rctx) (:rxns rctx))
               (pdf/document->spec form (:values rctx) (:rxns rctx)))
        idx  (count (get-in (documents/get-document documents doc-id) [:meta :reports]))
        file (io/file (or reports-dir "data/reports") doc-id (str "report-" idx ".pdf"))]
    (pdf/write! spec file)
    (append-report! documents doc-id {:at (System/currentTimeMillis) :by (:by intent)
                                      :pdf (.getPath file) :idx idx})))

;; --- external service call (SSRF-guarded) ---------------------------------

(defmethod perform! :http [{:keys [http-client]} _doc-id _form intent]
  (let [{:keys [url host-allow]} intent]
    (if (sources/host-allowed? host-allow url)
      (http/send! http-client (select-keys intent [:url :method :body :headers]))
      (log/warn "blocked :http effect — host not on the allowlist" {:url url :host-allow host-allow}))))

;; --- imports (layered on the engine's effect signal) ----------------------

(defmethod perform! :import [resources doc-id form intent]
  (docs/run-imports! resources form doc-id (:fields intent)))

(defn perform-all!
  "Perform every intent (from the engine's last transact, or an action's steps). A
   failing effect is logged and skipped — a side-effect failure never breaks the
   edit or transition."
  [resources doc-id form intents]
  (doseq [intent intents]
    (try (perform! resources doc-id form intent)
         (catch Throwable e (log/error e "effect failed" {:intent intent})))))
