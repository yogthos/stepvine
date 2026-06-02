(ns yogthos.stepvine.options
  "DB-sourced dropdown options (`:store/options`).

   A form field may declare `:options {:source :some/query}` instead of static
   `:values`; the options are resolved at render time from this store. Option
   sets live on disk as one EDN file per source — `{:id :clinics/active :options
   [...]}` — scanned from an options directory (the v1 'database' for reference
   data). A real database query slots in behind `resolve-source` without touching
   callers."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [yogthos.stepvine.sources :as sources]
   [yogthos.stepvine.terminology :as terminology]))

(defn- edn-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn load-dir
  "Load every *.edn option-set in `dir` into a {source-id -> options} map."
  [dir]
  (let [d (io/file dir)]
    (when-not (.isDirectory d)
      (throw (ex-info "Options directory not found" {:dir dir})))
    (into {}
          (map (fn [f] (let [m (edn/read-string (slurp f))] [(:id m) (:options m)])))
          (filter edn-file? (.listFiles d)))))

(defn- options-spec
  "Normalize a field's `:options` into a source spec (§15.6). The legacy shorthand
   `{:source :x}` (no :kind) means a named set from the options store."
  [options]
  (if (:kind options) options (assoc options :kind :options)))

(defn resolve-source
  "Options ([{:value .. :label ..} ...]) for a named source set, or nil. Thin
   accessor over the options store (back-compat)."
  [store source]
  (get store source))

(defn resolve-field-options
  "Build {field-id -> options} for every field whose definition declares an
   `:options` source, resolving each through the unified source resolver (§15.6) —
   so a field may use any source `:kind` (`:options` store set, inline `:static`
   data, …), not just a named store set."
  [store field-opts]
  (into {}
        (keep (fn [[id opts]]
                (when-let [options (:options opts)]
                  (if (map? options)
                    ;; a source spec ({:source …} / {:kind …}) → resolve via the store
                    (let [resolve (sources/resolve-source {:options-store store}
                                                          (options-spec options))]
                      [id (resolve)])       ; full list (no query)
                    ;; a literal option list ([{:value :label} …] / [[label value] …])
                    ;; declared inline on the field → use it verbatim
                    [id options]))))
        field-opts))

(defmethod ig/init-key :store/options
  [_ {:keys [dir term-dir]}]
  (let [loaded (load-dir dir)
        ;; coded value sets (§9ox) are folded into the same store, expanded from
        ;; FHIR ValueSets — so a :value-set source named by id resolves like any
        ;; option set, with terminology semantics applied at load.
        coded  (when term-dir (terminology/load-dir term-dir))
        store  (merge loaded coded)]
    (log/info "loaded option sources from" dir ":" (vec (keys loaded))
              (when (seq coded) (str "+ value sets: " (vec (keys coded)))))
    store))
