(ns yogthos.stepvine.edn-dir
  "Loading a directory of *.edn resource files into a map — the shared mechanics
   behind the forms / options / partials / terminology stores. Each store passes
   an `entry-fn` (a `File` → `[id value]` pair) and a missing-directory policy."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn edn-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn read-edn
  "Parse a `File`'s contents as EDN."
  [^java.io.File f]
  (edn/read-string (slurp f)))

(defn load-edn-dir
  "Read every *.edn file in `dir`, mapping each `File` through `entry-fn` (which
   returns an `[id value]` pair) into a map. `missing` is the not-a-directory
   policy: `:throw` (default) raises an ex-info, `:empty` returns `{}`."
  ([dir entry-fn] (load-edn-dir dir entry-fn :throw))
  ([dir entry-fn missing]
   (let [d (io/file dir)]
     (if (.isDirectory d)
       (into {} (map entry-fn) (filter edn-file? (.listFiles d)))
       (case missing
         :empty {}
         (throw (ex-info "EDN directory not found" {:dir dir})))))))
