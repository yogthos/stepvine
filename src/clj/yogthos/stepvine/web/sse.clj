(ns yogthos.stepvine.web.sse
  "Datastar SSE endpoint for a document: GET /form/:id/sse.

   Opens a persistent stream, registers it in the hub under the document, sends
   an initial signal sync so a late-joining connection catches up, and keeps the
   stream open until the client disconnects (the Datastar ring adapter requires
   blocking in on-open — see web.server). Connecting and disconnecting broadcast
   the updated presence count to peers. Field updates are broadcast from the
   session manager's on-update hook, not here.

   Written as a plain (streaming) ring handler rather than a Mycelium workflow,
   since an SSE stream is not an html-response."
  (:require
   [clojure.tools.logging :as log]
   [jsonista.core :as json]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.session :as session]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.ring :as ds-ring])
  (:import
   [java.util UUID]
   [java.util.concurrent CountDownLatch]))

(defn- broadcast-presence! [hub doc-id]
  ;; distinct signed-in users on the document (a user may have multiple tabs)
  (hub/broadcast-signals! hub doc-id {"presence" (count (hub/users hub doc-id))}))

(defn make-handler
  "Build the SSE ring handler closed over the hub and the document resources."
  [{:keys [hub] :as resources}]
  (fn [request]
    (let [doc-id  (get-in request [:path-params :id])
          conn-id (str (UUID/randomUUID))
          ;; identity from the signed session cookie, not a client-supplied param
          uid     (get-in request [:session :user-id])
          manager (:manager resources)]
      ;; ensure the document's session exists (recreated from persisted db)
      (docs/ensure! {:forms          (:forms resources)
                     :documents      (:documents resources)
                     :session-manager manager}
                    doc-id)
      (let [latch (CountDownLatch. 1)]
        (ds-ring/->sse-response
         request
         {ds-ring/on-open
          (fn [gen]
            (hub/register! hub doc-id conn-id gen uid)
            ;; Initial sync for a (re)connecting client: catch it up to the
            ;; current server state. Only non-empty signals are sent: the page's
            ;; data-signals seed already carries every field as "" (its empty
            ;; state rendered at page load), so re-sending "" here is redundant
            ;; *and* harmful — a late-arriving empty sync would clobber a value
            ;; the user just typed (in the window before this stream opened),
            ;; resetting their in-progress edit. Sending only populated signals
            ;; catches up real data without overwriting empty fields being edited.
            (when-let [s (session/current-maybe manager doc-id)]
              (let [populated (into {} (remove (comp #{""} val))
                                    (signals/session->signal-map s))]
                (when (seq populated)
                  (d*/patch-signals! gen (json/write-value-as-string populated)))))
            (broadcast-presence! hub doc-id)
            (.await latch))
          ds-ring/on-close
          (fn [_gen]
            (hub/unregister! hub doc-id conn-id)
            ;; free any field locks this client was holding, then update peers
            (session/release-user-locks! manager hub doc-id uid)
            (broadcast-presence! hub doc-id)
            (log/debug "sse closed" doc-id conn-id)
            (.countDown latch))})))))
