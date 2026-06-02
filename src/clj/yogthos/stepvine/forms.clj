(ns yogthos.stepvine.forms
  "Form/app store (`:store/forms`), pluggable behind the `FormStore` protocol.

   An *app* is its EDN (model/views/workflow/sources/validation) **plus its own
   CSS**. The store keeps the current *working* form per id (with CSS), and an
   **immutable version archive** keyed by `[id version]` with a content digest.

   Two backends:
   - **map/disk** (default) — EDN files in a directory + a duratom/atom archive;
   - **SQLite** (`:backend :sql`) — `forms` (working: id, edn, css) + `form_versions`
     (archive: form_id, version, digest, draft, edn). Schema is plain SQL over
     next.jdbc, so a Postgres backend slots in the same way later.

   Design notes drawn from (not copied from) a reference Postgres system:
   - the body is **EDN text**, not JSON — forms carry code (quoted fns) that JSON
     can't represent;
   - versioning uses an **explicit `:version` + content digest** (deterministic,
     identity-bearing) rather than a `created_at` ordering (which is ambiguous
     under clock skew);
   - the **working form is separate from the immutable archive**, and superseded
     versions are **sealed** (re-publishing throws) — the reference conflated the
     two and could silently mutate a published version;
   - CSS is a live column on the working form, **never** in the versioned archive."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [yogthos.stepvine.cascades :as cascades]
   [yogthos.stepvine.imports :as imports]
   [yogthos.stepvine.partials :as partials]
   [yogthos.stepvine.validation :as validation]
   [yogthos.stepvine.versions :as versions]))

;; --- Protocol -------------------------------------------------------------

(defprotocol FormStore
  (-form            [s id]   "The working form (raw EDN + :css) for id, or nil.")
  (-form-ids        [s]      "All form ids (keywords).")
  (-store-form!     [s form] "Upsert the working form (EDN + CSS).")
  (-archived        [s id v] "The archived form for [id v] (no CSS), or nil.")
  (-latest-published [s id]  "Highest published (non-draft) version number, or nil.")
  (-archived-digest [s id v] "Content digest of [id v], or nil.")
  (-publish!        [s form] "Archive form at its :version (sealing-checked). Returns {:id :version :digest}."))

;; --- Authoring (pure-ish helpers) -----------------------------------------

(defn- compile-import-effects
  "Add a form effect per event-triggered import, so an import is fired by the
   engine's effect signal (not the host inspecting changes)."
  [form]
  (let [effs (imports/->effects form)]
    (cond-> form
      (seq effs) (update-in [:data :effects] (fnil into []) effs))))

(defn prepare-form
  "Resolve a served form: splice partials (§15.9), compile declarative validation
   into error + :valid? reactions (§15.8), compile cascading-dropdown dependencies
   into Domino clearing events, and event-triggered imports into Domino effect
   signals. Public so the live editor can preview a form exactly as it will be
   served."
  [store form]
  (some->> form
           (partials/splice (:partials store))
           validation/compile-validations
           cascades/compile-cascades
           compile-import-effects))

(def ^:private prepare prepare-form)

(defn- edn-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn- read-form
  "Read a form's EDN, plus its sibling `<id>.css` (the app's own styling) loaded
   into `:css` when present — so an app is its EDN + its CSS."
  [^java.io.File f]
  (let [form (edn/read-string (slurp f))
        css  (io/file (.getParentFile f) (str (name (:id form)) ".css"))]
    (cond-> form (.isFile css) (assoc :css (slurp css)))))

(defn load-dir
  "Load every *.edn form file in `dir` into a {form-id -> form} map."
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

;; --- map/disk backend -----------------------------------------------------

(defrecord MapFormStore [dir forms versions partials]
  FormStore
  (-form [_ id] (get @forms (keyword id)))
  (-form-ids [_] (keys @forms))
  (-store-form! [_ form]
    (let [id (:id form)]
      (when dir
        (io/make-parents (io/file dir "_"))
        (spit (io/file dir (str (name id) ".edn")) (pr-str (dissoc form :css)))
        (when (:css form) (spit (io/file dir (str (name id) ".css")) (:css form))))
      (swap! forms assoc id form)
      id))
  (-archived [_ id v] (when versions (versions/get-version versions (keyword id) v)))
  (-latest-published [_ id] (when versions (versions/latest-version versions (keyword id))))
  (-archived-digest [_ id v] (when versions (:digest (get @versions [(keyword id) v]))))
  (-publish! [_ form] (when versions (versions/publish! versions (dissoc form :css)))))

(defn atom-store
  "An in-memory/disk form store (the default backend). `forms` may be a map or an
   atom; `versions` an archive atom (or nil); `partials` a partials map (or nil)."
  [{:keys [dir forms versions partials]}]
  (->MapFormStore dir
                  (if (instance? clojure.lang.IAtom forms) forms (atom (or forms {})))
                  versions
                  partials))

;; --- SQLite backend -------------------------------------------------------

(def ^:private sql-schema
  ["create table if not exists forms
      (id text primary key, edn text not null, css text, updated_at integer)"
   "create table if not exists form_versions
      (form_id text not null, version integer not null, digest text not null,
       draft integer not null default 0, edn text not null, published_at integer,
       primary key (form_id, version))"
   "create index if not exists idx_form_versions_form on form_versions(form_id)"])

(defrecord SqlFormStore [ds partials]
  FormStore
  (-form [_ id]
    (when-let [row (jdbc/execute-one! ds ["select edn, css from forms where id=?" (name id)])]
      (cond-> (edn/read-string (:forms/edn row))
        (:forms/css row) (assoc :css (:forms/css row)))))
  (-form-ids [_]
    (mapv (comp keyword :forms/id) (jdbc/execute! ds ["select id from forms"])))
  (-store-form! [_ form]
    (jdbc/execute! ds ["insert into forms(id,edn,css,updated_at) values(?,?,?,?)
                        on conflict(id) do update set edn=excluded.edn, css=excluded.css,
                        updated_at=excluded.updated_at"
                       (name (:id form)) (pr-str (dissoc form :css)) (:css form)
                       (System/currentTimeMillis)])
    (:id form))
  (-archived [_ id v]
    (some-> (jdbc/execute-one! ds ["select edn from form_versions where form_id=? and version=?" (name id) v])
            :form_versions/edn edn/read-string))
  (-latest-published [_ id]
    (:m (jdbc/execute-one! ds ["select max(version) m from form_versions where form_id=? and draft=0" (name id)])))
  (-archived-digest [_ id v]
    (:form_versions/digest
     (jdbc/execute-one! ds ["select digest from form_versions where form_id=? and version=?" (name id) v])))
  (-publish! [_ form]
    (let [id (name (:id form)) v (:version form 1) d (versions/digest (dissoc form :css))
          ex (jdbc/execute-one! ds ["select digest from form_versions where form_id=? and version=?" id v])
          higher (:c (jdbc/execute-one! ds ["select count(*) c from form_versions where form_id=? and version>?" id v]))]
      (cond
        (and ex (= d (:form_versions/digest ex))) nil          ; identical — no-op
        (and ex (pos? higher))                                  ; sealed (superseded)
        (throw (ex-info "Refusing to mutate a sealed form version; bump :version"
                        {:id id :version v}))
        :else
        (jdbc/execute! ds ["insert into form_versions(form_id,version,digest,draft,edn,published_at)
                            values(?,?,?,?,?,?)
                            on conflict(form_id,version) do update set digest=excluded.digest,
                            draft=excluded.draft, edn=excluded.edn, published_at=excluded.published_at"
                           id v d (if (:draft? form) 1 0) (pr-str (dissoc form :css))
                           (System/currentTimeMillis)]))
      {:id (:id form) :version v :digest d})))

;; --- Public API (backend-agnostic) ----------------------------------------

(defn get-form
  "The current working form by id, with partials spliced + validation compiled.
   Used for previews/builder + new-document listing; loaded documents resolve
   their pinned version via `get-form-version`."
  [store id]
  (prepare store (-form store (keyword id))))

(defn list-forms [store] (-form-ids store))

(defn raw-form
  "The raw working form (un-prepared, incl. :css) for editing/inspection, or nil."
  [store id]
  (-form store (keyword id)))

(defn latest-published
  "The highest published version number for a form, falling back to the working
   form's declared `:version`."
  [store id]
  (or (-latest-published store (keyword id))
      (:version (-form store (keyword id)) 1)))

(defn version-digest [store id v] (-archived-digest store (keyword id) v))

(defn get-form-version
  "The exact archived form for a pinned `[id version]` (partials spliced), falling
   back to the working form when the archive has no such entry."
  [store id v]
  (prepare store (or (-archived store (keyword id) v) (-form store (keyword id)))))

(defn save-form!
  "Persist the working form (EDN + CSS) and publish its version. CSS is live
   presentation — excluded from the archive — so a re-skin never spawns a version."
  [store form]
  (-store-form! store form)
  (-publish! store form)
  (:id form))

;; --- App CSS (app-owned styling, served live) -----------------------------

(defn css
  "The app's own CSS string, or nil."
  [store id]
  (:css (-form store (keyword id))))

(defn app-css-href
  "A cache-busting href for an app's *live* CSS (re-skins without redeploy), or
   nil when the app declares none."
  [store id]
  (when-let [c (css store id)]
    (str "/app/" (name id) "/style.css?v=" (subs (versions/digest {:css c}) 0 12))))

;; --- Component ------------------------------------------------------------

(defn- seed-from-dir!
  "Load every app from the on-disk forms directory into `store` (working + version)
   — the disk files seed a fresh DB-backed store; thereafter it is live in the DB."
  [store dir]
  (doseq [form (vals (load-dir dir))]
    (-store-form! store form)
    (-publish! store form)))

(defmethod ig/init-key :store/forms
  [_ {:keys [dir versions-file partials backend db-file]}]
  (case (or backend :atom)
    :sql (let [_  (io/make-parents (or db-file "data/apps.db"))
               ds (jdbc/get-datasource (str "jdbc:sqlite:" (or db-file "data/apps.db")))
               store (->SqlFormStore ds partials)]
           (doseq [stmt sql-schema] (jdbc/execute! ds [stmt]))
           (when (and dir (empty? (-form-ids store)))
             (seed-from-dir! store dir)
             (log/info "seeded SQLite app store from" dir))
           (log/info "apps backed by SQLite at" (or db-file "data/apps.db"))
           store)
    (let [store (->MapFormStore dir (atom (load-dir dir))
                                (versions/init-archive versions-file) partials)]
      (doseq [form (vals @(:forms store))] (-publish! store form))
      (log/info "loaded forms from" dir ":" (vec (-form-ids store)))
      store)))
