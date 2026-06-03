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
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [yogthos.stepvine.edn-dir :as edn-dir]
   [yogthos.stepvine.store :as store]
   [yogthos.stepvine.versions :as versions]))

;; --- Protocol -------------------------------------------------------------

(defprotocol FormStore
  (-form            [s id]   "The working form (raw EDN + :css) for id, or nil.")
  (-form-ids        [s]      "All form ids (keywords).")
  (-store-form!     [s form] "Upsert the working form (EDN + CSS).")
  (-archived        [s id v] "The archived form for [id v] (no CSS), or nil.")
  (-latest-published [s id]  "Highest published (non-draft) version number, or nil.")
  (-archived-digest [s id v] "Content digest of [id v], or nil.")
  (-publish!        [s form] "Archive form at its :version (sealing-checked). Returns {:id :version :digest}.")
  ;; --- Draft authoring slot (§lqj): WIP edits, separate from working/published
  (-draft           [s id]   "The draft form (EDN + :css) for id, or nil.")
  (-store-draft!    [s form] "Upsert the draft form (EDN + CSS).")
  (-discard-draft!  [s id]   "Drop the draft for id."))

;; Form compilation (partials splice + validation/cascade compile + import
;; effects) is a downstream concern — it lives in `yogthos.stepvine.forms-compile`
;; (`prepare-form`/`get-form`/`get-form-version`). This store returns raw EDN.

;; --- Loading helpers (pure EDN/disk) --------------------------------------

(defn- read-form
  "Read a form's EDN, plus its sibling `<id>.css` (the app's own styling) loaded
   into `:css` when present — so an app is its EDN + its CSS."
  [^java.io.File f]
  (let [form (store/read-edn (slurp f))
        css  (io/file (.getParentFile f) (str (name (:id form)) ".css"))]
    (cond-> form (.isFile css) (assoc :css (slurp css)))))

(defn load-dir
  "Load every *.edn form file in `dir` into a {form-id -> form} map."
  [dir]
  (edn-dir/load-edn-dir dir (fn [f] (let [form (read-form f)] [(:id form) form]))))

(defn load-form
  "Read a single form by id from a forms directory (default \"forms\")."
  ([id] (load-form "forms" id))
  ([dir id] (read-form (io/file dir (str (name id) ".edn")))))

;; --- map/disk backend -----------------------------------------------------

(defrecord MapFormStore [dir forms versions partials drafts]
  FormStore
  (-form [_ id] (get @forms (keyword id)))
  (-form-ids [_] (keys @forms))
  (-draft [_ id] (get @drafts (keyword id)))
  (-store-draft! [_ form] (swap! drafts assoc (keyword (:id form)) form) (:id form))
  (-discard-draft! [_ id] (swap! drafts dissoc (keyword id)) nil)
  (-store-form! [_ form]
    (let [id (:id form)]
      (when dir
        (io/make-parents (io/file dir "_"))
        (spit (io/file dir (str (name id) ".edn")) (store/write-edn (dissoc form :css)))
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
  [{:keys [dir forms versions partials drafts]}]
  (->MapFormStore dir
                  (if (instance? clojure.lang.IAtom forms) forms (atom (or forms {})))
                  versions
                  partials
                  (if (instance? clojure.lang.IAtom drafts) drafts (atom (or drafts {})))))

;; --- SQLite backend -------------------------------------------------------

(def ^:private sql-schema
  ["create table if not exists forms
      (id text primary key, edn text not null, css text, updated_at integer)"
   "create table if not exists form_versions
      (form_id text not null, version integer not null, digest text not null,
       draft integer not null default 0, edn text not null, published_at integer,
       primary key (form_id, version))"
   "create index if not exists idx_form_versions_form on form_versions(form_id)"
   ;; the single WIP draft per form (§lqj) — separate from the published working form
   "create table if not exists form_drafts
      (form_id text primary key, edn text not null, css text, updated_at integer)"])

(defrecord SqlFormStore [ds partials]
  FormStore
  (-form [_ id]
    (when-let [row (jdbc/execute-one! ds ["select edn, css from forms where id=?" (name id)])]
      (cond-> (store/read-edn (:forms/edn row))
        (:forms/css row) (assoc :css (:forms/css row)))))
  (-form-ids [_]
    (mapv (comp keyword :forms/id) (jdbc/execute! ds ["select id from forms"])))
  (-draft [_ id]
    (when-let [row (jdbc/execute-one! ds ["select edn, css from form_drafts where form_id=?" (name id)])]
      (cond-> (store/read-edn (:form_drafts/edn row))
        (:form_drafts/css row) (assoc :css (:form_drafts/css row)))))
  (-store-draft! [_ form]
    (jdbc/execute! ds ["insert into form_drafts(form_id,edn,css,updated_at) values(?,?,?,?)
                        on conflict(form_id) do update set edn=excluded.edn, css=excluded.css,
                        updated_at=excluded.updated_at"
                       (name (:id form)) (store/write-edn (dissoc form :css)) (:css form)
                       (System/currentTimeMillis)])
    (:id form))
  (-discard-draft! [_ id]
    (jdbc/execute! ds ["delete from form_drafts where form_id=?" (name id)]) nil)
  (-store-form! [_ form]
    (jdbc/execute! ds ["insert into forms(id,edn,css,updated_at) values(?,?,?,?)
                        on conflict(id) do update set edn=excluded.edn, css=excluded.css,
                        updated_at=excluded.updated_at"
                       (name (:id form)) (store/write-edn (dissoc form :css)) (:css form)
                       (System/currentTimeMillis)])
    (:id form))
  (-archived [_ id v]
    (some-> (jdbc/execute-one! ds ["select edn from form_versions where form_id=? and version=?" (name id) v])
            :form_versions/edn store/read-edn))
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
                           id v d (if (:draft? form) 1 0) (store/write-edn (dissoc form :css))
                           (System/currentTimeMillis)]))
      {:id (:id form) :version v :digest d})))

;; --- Public API (backend-agnostic) ----------------------------------------
;; Prepared reads (partials spliced + validation compiled) — `get-form` /
;; `get-form-version` — live in `yogthos.stepvine.forms-compile`. The store
;; itself only exposes raw reads.

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

(defn save-form!
  "Persist the working form (EDN + CSS) and publish its version. CSS is live
   presentation — excluded from the archive — so a re-skin never spawns a version."
  [store form]
  (-store-form! store form)
  (-publish! store form)
  (:id form))

;; --- Drafts authoring flow (§lqj) -----------------------------------------
;; A form has at most one WIP *draft*, edited in the live editor and kept apart
;; from the published working form. Authors iterate on the draft (no new version,
;; new documents keep using the last published form) and Publish when ready.

(defn draft
  "The raw draft form (EDN + :css) for id, or nil when none is pending."
  [store id]
  (-draft store (keyword id)))

(defn has-draft? [store id] (boolean (-draft store (keyword id))))

(defn for-editing
  "The form the editor should open: the pending draft if one exists, else the
   published working form."
  [store id]
  (or (-draft store (keyword id)) (-form store (keyword id))))

(defn save-draft!
  "Save WIP edits as the form's draft — no publish, the working form and the
   version new documents pin to are untouched."
  [store form]
  (-store-draft! store form)
  (:id form))

(defn discard-draft!
  "Drop a pending draft; the published working form is unaffected."
  [store id]
  (-discard-draft! store (keyword id))
  (keyword id))

(defn publish-draft!
  "Promote the pending draft to the working form AND publish its version, then
   clear the draft. Returns `{:id :version :digest}`, or nil if there is no draft.
   Throws (sealing guard) if the draft's `:version` collides with a sealed one —
   the author must bump `:version` to publish a change."
  [store id]
  (when-let [form (-draft store (keyword id))]
    (-store-form! store form)
    (let [pub (-publish! store form)]
      (-discard-draft! store (keyword id))
      (or pub {:id (keyword id) :version (:version form 1)}))))

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
    :sql (let [db    (or db-file "data/apps.db")
               ds    (store/sqlite-datasource db sql-schema)
               store (->SqlFormStore ds partials)]
           (when (and dir (empty? (-form-ids store)))
             (seed-from-dir! store dir)
             (log/info "seeded SQLite app store from" dir))
           (log/info "apps backed by SQLite at" db)
           store)
    (let [store (->MapFormStore dir (atom (load-dir dir))
                                (versions/init-archive versions-file) partials (atom {}))]
      (doseq [form (vals @(:forms store))] (-publish! store form))
      (log/info "loaded forms from" dir ":" (vec (-form-ids store)))
      store)))
