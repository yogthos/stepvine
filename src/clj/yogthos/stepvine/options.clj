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
   [integrant.core :as ig]))

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

(defn resolve-source
  "Options ([{:value .. :label ..} ...]) for a named source, or nil."
  [store source]
  (get store source))

(defn resolve-field-options
  "Build {field-id -> options} for every field whose definition declares an
   `:options {:source ..}`."
  [store field-opts]
  (into {}
        (keep (fn [[id opts]]
                (when-let [src (get-in opts [:options :source])]
                  [id (resolve-source store src)])))
        field-opts))

(defmethod ig/init-key :store/options
  [_ {:keys [dir]}]
  (let [loaded (load-dir dir)]
    (log/info "loaded option sources from" dir ":" (vec (keys loaded)))
    loaded))
