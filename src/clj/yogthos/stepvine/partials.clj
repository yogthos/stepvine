(ns yogthos.stepvine.partials
  "Reusable definition partials (PLAN.md §15.9).

   A partial is a named, separately-stored block of form definition (a markup
   subtree, a section, …). A form references one with an `{:include <id>}` node;
   `splice` replaces every such node with the partial's value, recursively.

   Splicing happens when a form is **served** (see `forms`), so a fix to a shared
   partial reaches every form that includes it — unlike the reference, which
   inlined fragments at write-time and left stale copies in already-saved forms.

   Partials live on disk as one EDN file each — `{:id :address :partial <value>}`
   — scanned from a partials directory; a real database slots in behind the same
   map API."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [integrant.core :as ig]))

(defn- edn-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn load-dir
  "Load every *.edn partial in `dir` into a {id -> value} map. A missing directory
   is not an error — partials are optional."
  [dir]
  (let [d (io/file dir)]
    (if (.isDirectory d)
      (into {}
            (map (fn [f] (let [m (edn/read-string (slurp f))] [(:id m) (:partial m)])))
            (filter edn-file? (.listFiles d)))
      (do (log/info "no partials directory at" dir "- skipping") {}))))

(defn splice
  "Replace every `{:include <id>}` node in `form` with `(get registry id)`,
   recursively. Unknown includes are left in place (so they can be reported)."
  [registry form]
  (if (empty? registry)
    form
    (walk/postwalk
     (fn [node]
       (if (and (map? node) (contains? node :include) (contains? registry (:include node)))
         (get registry (:include node))
         node))
     form)))

(defmethod ig/init-key :store/partials
  [_ {:keys [dir]}]
  (let [loaded (load-dir dir)]
    (log/info "loaded partials from" dir ":" (vec (keys loaded)))
    loaded))
