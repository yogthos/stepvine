(ns yogthos.stepvine.forms
  "Form-definition store (`:store/forms`).

   Form definitions are the *templates* — raw EDN, one file per form, stored on
   disk in a forms directory (the v1 'database' for form definitions). The store
   scans that directory at startup and keys each form by its `:id`; saving a form
   writes it back to disk. A real database can replace this behind the same
   get-form / save-form! / list-forms API.

   A form's `:data` section is a Domino schema; `:views` describe presentation.
   Event/reaction fns are left as quoted lists and evaluated safely via sci when
   a session is created (yogthos.stepvine.editor.impl)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn- edn-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn- read-form [^java.io.File f]
  (edn/read-string (slurp f)))

(defn load-dir
  "Load every *.edn form file in `dir` into a {form-id -> form} map, keyed by the
   form's own :id."
  [dir]
  (let [d (io/file dir)]
    (when-not (.isDirectory d)
      (throw (ex-info "Forms directory not found" {:dir dir})))
    (into {}
          (map (fn [f] (let [form (read-form f)] [(:id form) form])))
          (filter edn-file? (.listFiles d)))))

(defn load-form
  "Read a single form by id from a forms directory (default \"forms\")."
  ([id] (load-form "forms" id))
  ([dir id] (read-form (io/file dir (str (name id) ".edn")))))

;; --- Store API ------------------------------------------------------------

(defn get-form
  "Look up a loaded form by id (keyword or string). Returns the raw form or nil."
  [store id]
  (get @(:forms store) (keyword id)))

(defn list-forms
  "Ids of all loaded forms."
  [store]
  (keys @(:forms store)))

(defn save-form!
  "Persist a form to disk and update the in-memory store."
  [store form]
  (let [id (:id form)]
    (spit (io/file (:dir store) (str (name id) ".edn")) (pr-str form))
    (swap! (:forms store) assoc id form)
    id))

(defmethod ig/init-key :store/forms
  [_ {:keys [dir]}]
  (let [loaded (load-dir dir)]
    (log/info "loaded forms from" dir ":" (vec (keys loaded)))
    {:dir dir :forms (atom loaded)}))
