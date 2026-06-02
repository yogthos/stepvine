(ns yogthos.stepvine.imports
  "External-data injection / hydration (PLAN.md §15.7).

   A form declares `:imports`, each pulling from a named `:source` (§15.6) at one
   or more triggers and mapping the fetched data into document fields. Three
   properties from the roadmap, improving on the reference:
     - **lazy** — only imports whose `:on` includes the current trigger run, so a
       source is fetched only when actually needed;
     - **diff-based** — only fields whose value actually changes are emitted, so
       re-running an import is idempotent;
     - **chainable** — a later import sees an earlier one's pending changes, so
       imports can feed each other within one pass.

   Spec (a vector of):
     {:on     #{:create | :open | :event/<field>}   ; triggers
      :from   <source-id>                            ; a key in the form's :sources
      :params {<param-key> [<doc-path>] ...}         ; source params ← document
      :map    {<field-id>  [<data-path>] ...}        ; document fields ← fetched data
      :into   :data}                                 ; :data (default) | :meta

   Pure: `run` returns `[[field value] ...]`; the caller transacts them.")

(defn triggered
  "Imports whose `:on` set includes any of `triggers` (a single trigger or a
   collection), in declared order (so chaining is preserved)."
  [imports triggers]
  (let [ts (if (coll? triggers) (set triggers) #{triggers})]
    (filter #(some ts (:on %)) imports)))

(defn event-trigger
  "The trigger keyword for a changed field, e.g. :weight -> :event/weight."
  [field-id]
  (keyword "event" (name field-id)))

(defn- event-fields
  "Field ids an import's `:on` event-triggers reference (:event/patient-id ->
   :patient-id); :create/:open triggers (handled at creation) are ignored."
  [import]
  (keep (fn [t] (when (= "event" (namespace t)) (keyword (name t)))) (:on import)))

(defn ->effects
  "Form effects (data) that emit an `:import` intent when an event-triggered
   import's trigger fields change — so imports are layered on the engine's effect
   signal. Handlers are quoted forms (they survive the sci round-trip)."
  [form]
  (keep (fn [imp]
          (when-let [fields (seq (event-fields imp))]
            {:on      (vec fields)
             :handler (list 'fn ['_] {:kind :import :fields (vec fields)})}))
        (:imports form)))

(defn- as-path [p] (if (vector? p) p [p]))

(defn run
  "Run the imports triggered by `trigger` (a single trigger keyword or a set —
   e.g. the triggers for every field that changed in a transaction).
     - resolve : (fn [source-id] -> source-fn)   ; a source resolver (§15.6)
     - read    : (fn [doc-path]  -> value)       ; current document value at a path
   Returns `[[field-id value] ...]` of *changed* fields, chaining so a later
   import reads an earlier one's pending output."
  [imports trigger resolve read]
  (reduce
   (fn [pending imp]
     (let [pmap (into {} pending)
           ;; read prefers a pending change (chaining), else the live document
           rd   (fn [path] (let [p (as-path path)]
                             (if (contains? pmap (first p)) (get pmap (first p)) (read p))))
           params  (into {} (map (fn [[k path]] [k (rd path)])) (:params imp))
           src     (resolve (:from imp))
           data    (when src (src params))
           changes (when data
                     (keep (fn [[field path]]
                             (let [v (get-in data (as-path path))]
                               (when (and (some? v) (not= v (rd [field])))
                                 [field v])))
                           (:map imp)))]
       (into pending (vec changes))))
   []
   (triggered imports trigger)))

(defn source-ctx
  "Build the resolver context for sources used by imports, from cell resources."
  [{:keys [options-store patient-client request-fn base-url]}]
  (cond-> {:options-store options-store}
    patient-client (assoc :clients {:patient patient-client})
    request-fn     (assoc :request-fn request-fn)
    base-url       (assoc :base-url base-url)))
