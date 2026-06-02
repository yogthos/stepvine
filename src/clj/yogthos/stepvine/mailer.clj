(ns yogthos.stepvine.mailer
  "Outbound email for workflow `:email` steps. Pluggable behind a small protocol:

     RecordingMailer — dev/test: keeps an in-memory outbox (inspectable), sends
                       nothing. The default.
     SmtpMailer      — prod: delivers via SMTP (postal).

   A workflow `:email` step compiles to an `[:email {:to :subject :body}]`
   directive; `directives/apply!` calls `send!` with the resources' `:mailer`.
   `send!`/`outbox` no-op on a nil mailer, so a workflow with an email step still
   runs when no mailer is configured."
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [postal.core :as postal]))

(defprotocol Mailer
  (-send!  [m message] "Deliver {:to :subject :body}; returns the (possibly stamped) message.")
  (-outbox [m]         "Recorded messages (recording mailer), or nil."))

(defrecord RecordingMailer [log]
  Mailer
  (-send! [_ message]
    (let [m (assoc message :at (System/currentTimeMillis))]
      (swap! log conj m)
      (log/info "email (recording)" {:to (:to message) :subject (:subject message)})
      m))
  (-outbox [_] @log))

(defrecord SmtpMailer [conn from]
  Mailer
  (-send! [_ {:keys [to subject body]}]
    (postal/send-message conn {:from from :to to :subject (str subject) :body (str body)}))
  (-outbox [_] nil))

(defn recording
  "A fresh recording mailer (empty outbox)."
  [] (->RecordingMailer (atom [])))

(defn send!
  "Send `message` ({:to :subject :body}) through `mailer`; no-op (nil) when there
   is no mailer."
  [mailer message]
  (when (satisfies? Mailer mailer) (-send! mailer message)))

(defn outbox
  "The recording mailer's sent-message log, or nil."
  [mailer]
  (when (satisfies? Mailer mailer) (-outbox mailer)))

(defmethod ig/init-key :mailer/mailer [_ {:keys [kind smtp from]}]
  (case kind
    :smtp (do (log/info "mailer: SMTP" (select-keys smtp [:host :port :tls]))
              (->SmtpMailer smtp (or from "stepvine@localhost")))
    (do (log/info "mailer: recording (no outbound delivery)")
        (recording))))
