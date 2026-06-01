(ns yogthos.stepvine.hub
  "Datastar connection hub (`:datastar/hub`).

   Tracks open SSE generators keyed by document, so a change to a document can be
   broadcast to every connection currently viewing it — the fan-out that makes
   editing multi-user. State shape:

     {doc-id {conn-id {:gen <sse-gen> :user <uid>}}}

   Phase 0's single global connection set is replaced by this per-document map."
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [starfederation.datastar.clojure.api :as d*]))

(defn register!
  [hub doc-id conn-id gen user]
  (swap! hub assoc-in [doc-id conn-id] {:gen gen :user user}))

(defn unregister!
  [hub doc-id conn-id]
  (swap! hub update doc-id dissoc conn-id))

(defn connections
  [hub doc-id]
  (vals (get @hub doc-id)))

(defn connection-count
  [hub doc-id]
  (count (get @hub doc-id)))

(defn users
  "Distinct user ids currently connected to a document."
  [hub doc-id]
  (into #{} (keep :user) (connections hub doc-id)))

(defn broadcast-signals!
  "Patch `signals` (a Clojure map; JSON-merge-patch semantics, nil deletes) into
   every connection on the document."
  [hub doc-id signals]
  (when (seq (get @hub doc-id))
    (let [payload (json/write-value-as-string signals)]
      (doseq [{:keys [gen]} (connections hub doc-id)]
        (d*/patch-signals! gen payload)))))

(defn broadcast-elements!
  "Patch an HTML fragment (morphed by element id) into every connection on the
   document. Used for structural changes like adding/removing collection items."
  [hub doc-id html]
  (doseq [{:keys [gen]} (connections hub doc-id)]
    (d*/patch-elements! gen html)))

(defn ping-all!
  "Write a no-op signal patch to every open connection across all documents.

   This is the keep-alive that makes the (thread-per-connection) ring adapter
   detect abruptly-dropped clients: the adapter only notices a broken connection
   when a write throws IOException, which it turns into close-sse! -> on-close
   (freeing the parked request thread and releasing the user's locks). Without
   periodic writes an idle document's dead connections would leak threads/locks
   until the next real broadcast."
  [hub]
  (doseq [[_doc-id conns] @hub
          [_conn-id {:keys [gen]}] conns]
    (d*/patch-signals! gen "{}")))

(defmethod ig/init-key :datastar/hub
  [_ _]
  (log/info "datastar hub started")
  (atom {}))

;; --- Heartbeat ------------------------------------------------------------

(defmethod ig/init-key :datastar/heartbeat
  [_ {:keys [hub interval-ms] :or {interval-ms 20000}}]
  (log/info "datastar heartbeat started (interval" interval-ms "ms)")
  (let [running (atom true)
        worker  (future
                  (loop []
                    (Thread/sleep interval-ms)
                    (when @running
                      (try (ping-all! hub)
                           (catch Throwable e (log/debug "heartbeat ping error" e)))
                      (recur))))]
    {:running running :worker worker}))

(defmethod ig/halt-key! :datastar/heartbeat
  [_ {:keys [running worker]}]
  (reset! running false)
  (future-cancel worker))
