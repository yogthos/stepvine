(ns yogthos.stepvine.web.server
  "HTTP server component backed by Jetty (info.sunng/ring-jetty9-adapter).

   We run Jetty in its default *synchronous* mode. SSE streams are kept open by
   the Datastar ring adapter blocking inside its `on-open` callback for the life
   of the connection (see web.sse); the adapter writes the response body
   synchronously via `ring.core.protocols/StreamableResponseBody`, so async mode
   would buy nothing here — it completes the response as soon as the body write
   returns. Sync mode also lets every ordinary handler stay a plain
   `[request] -> response` function.

   TRADE-OFF: this adapter holds one server thread per open SSE connection. We
   raise `:max-threads` accordingly. For high connection counts the http-kit
   Datastar adapter (true async, no thread-per-connection) would be the swap;
   the SSE hub is abstracted so that change stays localized.

   We define our own `:server/http` Integrant key (rather than kit-undertow /
   kit-jetty) so we control the thread pool and avoid their sync handler shim."
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [ring.adapter.jetty9 :as jetty]))

(defmethod ig/init-key :server/http
  [_ {:keys [handler host port] :as opts}]
  (log/info "starting HTTP server (jetty) on" (str host ":" port))
  (jetty/run-jetty
   handler
   (merge
    ;; headroom for thread-per-connection SSE streams
    {:max-threads 250}
    (-> opts
        (dissoc :handler)
        (assoc :join? false)))))

(defmethod ig/halt-key! :server/http
  [_ server]
  (log/info "stopping HTTP server")
  (jetty/stop-server server))
