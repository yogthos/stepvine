(ns yogthos.stepvine.effects
  "Host-side performer for the side-effect *intents* the Domino engine emits when a
   document's data changes (see `editor.data/*effect-sink*`).

   The split the engine gives us: events change document data; **effects** are the
   engine's outbound signals (change-triggered, in DAG order); this layer turns
   each emitted intent into an actual side effect — sending email, broadcasting a
   notice, running an import — using host resources. New effect kinds are one
   `defmethod`; the engine and the form schema don't change."
  (:require
   [clojure.tools.logging :as log]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.mailer :as mailer]))

(defmulti perform!
  "Perform one effect intent the engine emitted. Dispatch on `:kind`."
  (fn [_resources _doc-id _form intent] (:kind intent)))

(defmethod perform! :default [_ _ _ intent]
  (log/warn "unhandled effect intent" intent))

;; send an email (the form effect returns {:kind :email :to :subject :body})
(defmethod perform! :email [{:keys [mailer]} _doc-id _form intent]
  (mailer/send! mailer (select-keys intent [:to :subject :body])))

;; broadcast a transient in-app notice to everyone on the document
(defmethod perform! :notify [{:keys [hub]} doc-id _form intent]
  (hub/broadcast-signals! hub doc-id {"notice" (str (:message intent))}))

;; run the document's imports for the trigger fields (fetch + write back) —
;; imports are layered on the effect signal
(defmethod perform! :import [resources doc-id form intent]
  (docs/run-imports! resources form doc-id (:fields intent)))

(defn perform-all!
  "Perform every intent the engine emitted in the last transact. An effect that
   throws is logged and skipped — a side-effect failure never breaks the edit."
  [resources doc-id form intents]
  (doseq [intent intents]
    (try (perform! resources doc-id form intent)
         (catch Throwable e (log/error e "effect failed" {:intent intent})))))
