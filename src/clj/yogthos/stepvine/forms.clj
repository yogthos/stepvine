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
   [integrant.core :as ig]
   [yogthos.stepvine.partials :as partials]
   [yogthos.stepvine.validation :as validation]
   [yogthos.stepvine.versions :as versions]))

(defn- prepare
  "Resolve a served form: splice partials (§15.9) then compile its declarative
   validation into error + :valid? reactions (§15.8)."
  [store form]
  (some->> form
           (partials/splice (:partials store))
           validation/compile-validations))

(defn- edn-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn- read-form
  "Read a form's EDN, plus its sibling `<id>.css` (the app's own styling, §app-css)
   loaded into `:css` when present — so an app is its EDN + its CSS."
  [^java.io.File f]
  (let [form (edn/read-string (slurp f))
        css  (io/file (.getParentFile f) (str (name (:id form)) ".css"))]
    (cond-> form (.isFile css) (assoc :css (slurp css)))))

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
  "Look up the current *authoring* (working) form by id (keyword or string), with
   any `{:include ..}` partials spliced (§15.9). Used for previews/builder +
   new-document listing; loaded documents resolve their pinned version via
   `get-form-version`."
  [store id]
  (prepare store (get @(:forms store) (keyword id))))

(defn list-forms
  "Ids of all loaded forms."
  [store]
  (keys @(:forms store)))

;; --- Versioning (§15.1) ---------------------------------------------------

(defn latest-published
  "The highest published (non-draft) version number for a form, from the archive,
   falling back to the authoring form's declared `:version` when no archive entry
   exists (e.g. legacy/test stores)."
  [store id]
  (or (when-let [a (:versions store)] (versions/latest-version a (keyword id)))
      (:version (get-form store id) 1)))

(defn version-digest
  "The content digest of a published `[id version]`, or nil."
  [store id v]
  (when-let [a (:versions store)]
    (:digest (get @a [(keyword id) v]))))

;; --- App CSS (app-owned styling, served live) -----------------------------

(defn css
  "The app's own CSS string (loaded from its sibling `<id>.css`), or nil."
  [store id]
  (:css (get @(:forms store) (keyword id))))

(defn app-css-href
  "A cache-busting href for an app's *live* CSS (re-skins without redeploy), or
   nil when the app declares none. CSS is presentation — served from the current
   working form, not version-pinned with the document."
  [store id]
  (when-let [c (css store id)]
    (str "/app/" (name id) "/style.css?v=" (subs (versions/digest {:css c}) 0 12))))

(defn get-form-version
  "Resolve the exact archived form for a pinned `[id version]`, with partials
   spliced (§15.9). Falls back to the current authoring form when the archive has
   no such entry (legacy documents or archive-less test stores)."
  [store id v]
  (if-let [archived (when-let [a (:versions store)] (versions/get-version a (keyword id) v))]
    (prepare store archived)
    (get-form store id)))

(defn save-form!
  "Persist a form: its EDN (data/workflow/views) to `<id>.edn`, its app CSS to the
   sibling `<id>.css`, update the in-memory working copy, and publish the version.
   CSS is excluded from the versioned archive — it is live presentation, not pinned
   document content — so a re-skin never spawns a spurious form version."
  [store form]
  (let [id (:id form)]
    (spit (io/file (:dir store) (str (name id) ".edn")) (pr-str (dissoc form :css)))
    (when (:css form) (spit (io/file (:dir store) (str (name id) ".css")) (:css form)))
    (swap! (:forms store) assoc id form)
    (when-let [a (:versions store)] (versions/publish! a (dissoc form :css)))
    id))

(defmethod ig/init-key :store/forms
  [_ {:keys [dir versions-file partials]}]
  (let [loaded  (load-dir dir)
        archive (versions/init-archive versions-file)]
    (versions/publish-all! archive (map #(dissoc % :css) (vals loaded)))
    (log/info "loaded forms from" dir ":" (vec (keys loaded))
              "— archived versions for" (count loaded) "forms")
    {:dir dir :forms (atom loaded) :versions archive :partials partials}))
