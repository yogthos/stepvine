(ns yogthos.stepvine.web.request
  "Parsing the inbound Datastar request: the posted signals JSON (and the common
   `rev` optimistic-concurrency token). Datastar puts the client's signal state in
   the request (query param on GET, body on POST); a malformed/absent payload is
   treated as no signals rather than an error."
  (:require
   [jsonista.core :as json]
   [starfederation.datastar.clojure.api :as d*]))

(defn read-signals
  "The posted Datastar signals as a `{string -> value}` map, or `{}` if absent or
   unparseable."
  [req]
  (try (json/read-value (d*/get-signals req))
       (catch Exception _ {})))

(defn read-rev
  "The optimistic-concurrency `rev` token from the posted signals as a long, or
   nil if absent/non-numeric."
  [req]
  (try (some-> (read-signals req) (get "rev") long)
       (catch Exception _ nil)))
