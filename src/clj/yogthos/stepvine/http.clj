(ns yogthos.stepvine.http
  "Outbound HTTP transport for the workflow `:http` step (call an external service /
   POST a FHIR bundle). Pluggable behind a small protocol, like the mailer:

     RecordingClient — dev/test: records requests to an inspectable log, sends
                       nothing. The default.
     JdkClient       — prod: real requests via java.net.http (no extra dep).

   The SSRF host allowlist is enforced by the caller (`effects/perform! :http`)
   with `sources/host-allowed?`; this namespace is just the transport."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [jsonista.core :as json])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(defprotocol HttpClientP
  (-send! [c request] "Perform {:url :method :body :headers}; returns {:status …}.")
  (-log   [c]         "Recorded requests (recording client), or nil."))

(defrecord RecordingClient [log]
  HttpClientP
  (-send! [_ request]
    (let [r (assoc request :at (System/currentTimeMillis))]
      (swap! log conj r)
      (log/info "http (recording)" {:method (:method request) :url (:url request)})
      {:status 200 :recorded true}))
  (-log [_] @log))

(defrecord JdkClient [client]
  HttpClientP
  (-send! [_ {:keys [url method body headers]}]
    (let [json    (json/write-value-as-string body)
          builder (-> (HttpRequest/newBuilder (URI/create url))
                      (.method (str/upper-case (name (or method :post)))
                               (HttpRequest$BodyPublishers/ofString json))
                      (.header "Content-Type" "application/json"))
          builder (reduce (fn [b [k v]] (.header b (name k) (str v))) builder headers)
          resp    (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)}))
  (-log [_] nil))

(defn recording [] (->RecordingClient (atom [])))

(defn send!
  "POST/whatever `request` ({:url :method :body :headers}) through `client`;
   no-op (nil) when there is no client."
  [client request]
  (when (satisfies? HttpClientP client) (-send! client request)))

(defn recorded
  "The recording client's request log, or nil."
  [client]
  (when (satisfies? HttpClientP client) (-log client)))

(defmethod ig/init-key :http/client [_ {:keys [kind]}]
  (case kind
    :real (do (log/info "http client: real (java.net.http)") (->JdkClient (HttpClient/newHttpClient)))
    (do (log/info "http client: recording (no outbound calls)") (recording))))
