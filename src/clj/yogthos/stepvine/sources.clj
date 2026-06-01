(ns yogthos.stepvine.sources
  "Pluggable data sources (PLAN.md §15.6).

   A form declares named sources; `resolve-source` compiles a spec into a uniform
   resolver fn via a single multimethod on `:kind` — the one extension point for
   every source (option lists, external fetches), so adding a backend is one
   `defmethod`. The reference split this across a datasource multimethod *and* a
   hard-coded `case` for hydration sources; we unify them.

   Two calling shapes share the multimethod (a given source is used in one role):
     - **option kinds** (`:static`, `:options`) → `(fn ([] all) ([query] filtered))`
       returning an options list, filtered case-insensitively on the label.
     - **fetch kinds** (`:client`, `:http`) → `(fn [params] data)` returning a map.

   Remote (`:http`) calls are server-mediated and guarded by a per-source **host
   allowlist** (closing the SSRF gap the reference left open) plus a param
   allowlist. The HTTP transport is injected (`ctx :request-fn`) so the resolver
   stays dependency-free and unit-testable."
  (:require
   [clojure.string :as str])
  (:import
   [java.net URI]))

;; --- option helpers -------------------------------------------------------

(defn- option-label [o]
  (cond (map? o) (:label o) (vector? o) (first o) :else o))

(defn- filter-options
  "Options whose label contains `query` (case-insensitive). Blank query → all."
  [options query]
  (if (str/blank? (str query))
    options
    (let [q (str/lower-case (str query))]
      (filterv #(str/includes? (str/lower-case (str (option-label %))) q) options))))

;; --- host allowlist (SSRF guard) ------------------------------------------

(defn host-allowed?
  "True when the URL's host is on `allow` (a seq of hostnames). A nil/empty
   allowlist denies all remote hosts — secure by default."
  [allow url]
  (boolean (when (seq allow)
             (some #(= % (.getHost (URI. url))) allow))))

;; --- resolver multimethod -------------------------------------------------

(defmulti resolve-source
  "Compile a source spec into a resolver fn. Dispatch on `:kind`."
  (fn [_ctx spec] (:kind spec)))

(defmethod resolve-source :default [_ spec]
  (throw (ex-info "Unknown source kind" {:kind (:kind spec) :spec spec})))

(defmethod resolve-source :static
  [_ctx {:keys [data]}]
  (fn ([] data) ([query] (filter-options data query))))

(defmethod resolve-source :options
  [{:keys [options-store]} {:keys [source]}]
  (let [data (get options-store source [])]
    (fn ([] data) ([query] (filter-options data query)))))

(defmethod resolve-source :client
  [{:keys [clients]} {:keys [client key]}]
  (let [f (get clients client)]
    (fn [params] (when f (f (if key (get params key) params))))))

(defmethod resolve-source :http
  [{:keys [request-fn base-url]} {:keys [method url query-key host-allow allow]}]
  (let [full (str base-url url)]
    (when-not (host-allowed? host-allow full)
      (throw (ex-info "Source host is not on the allowlist" {:url full :host-allow host-allow})))
    (fn [params]
      (let [method  (or method :get)
            params* (cond-> (or params {}) allow (select-keys (vec allow)))
            payload (if query-key {query-key (get params* query-key)} params*)
            req     (assoc {:method method :url full}
                           (if (= :get method) :query-params :form-params)
                           payload)]
        (request-fn req)))))
