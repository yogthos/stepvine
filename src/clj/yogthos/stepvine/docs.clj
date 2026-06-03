(ns yogthos.stepvine.docs
  "Document service layer — ties together the form store, document store and
   session manager.

   A document is **pinned** to the exact form version it was created against
   (§15.1): `ensure!` loads that frozen version from the archive and recreates the
   live session from the persisted db — it never silently migrates a document onto
   a newer form. Moving a document forward is the explicit, opt-in `rebase!`."
  (:require
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms-compile :as forms-compile]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.imports :as imports]
   [yogthos.stepvine.migrations :as migrations]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.sources :as sources]
   [yogthos.stepvine.editor.impl :as impl]))

(defn run-imports!
  "Run any imports a change to `fids` triggers: resolve each import's source
   (§15.6), fetch lazily, and transact the diff-based mapped changes. `fids` is one
   field id or a collection of them — the set of fields that changed in a
   transaction — so an import reacts to a cascaded change too. Shared by the
   field-edit cell and index-based creation."
  [{:keys [session-manager] :as resources} form-raw doc-id fids]
  (let [ctx      (imports/source-ctx resources)
        resolve  (fn [sid] (when-let [spec (get-in form-raw [:sources sid])]
                             (sources/resolve-source ctx spec)))
        read     (fn [path] (session/value session-manager doc-id (first path)))
        triggers (into #{} (map imports/event-trigger)
                       (if (coll? fids) fids [fids]))
        changes  (imports/run (:imports form-raw) triggers resolve read)]
    (when (seq changes)
      (session/apply-change! session-manager doc-id changes))))

(defn- hydrate-value
  "Resolve a creation-time hydration spec against the creating `user` and the
   clock: `{:session [path…]}` reads the user record, `{:today fmt?}` the date,
   `{:now true}` a timestamp, `{:value v}` a literal."
  [user spec]
  (cond
    (contains? spec :session) (get-in user (vec (:session spec)))
    (contains? spec :today)   (.format (java.text.SimpleDateFormat.
                                        (if (string? (:today spec)) (:today spec) "yyyy-MM-dd"))
                                        (java.util.Date.))
    (contains? spec :now)     (System/currentTimeMillis)
    (contains? spec :value)   (:value spec)
    :else                     nil))

(defn hydrate!
  "Populate fields declared in the form's `:hydrate` map from the creation context
   — the creating `user` (`:session` path), today/now, or a literal. Runs once, at
   document creation, so e.g. `created-by`/`created-on` are stamped without the
   form author writing any code."
  [{:keys [session-manager] :as _resources} form doc-id user]
  (let [changes (keep (fn [[fid spec]]
                        (when-some [v (hydrate-value user spec)]
                          [(keyword fid) v]))
                      (:hydrate form))]
    (when (seq changes)
      (session/apply-change! session-manager doc-id (vec changes)))))

(defn ensure!
  "Ensure the live session for `doc-id` exists, loading its persisted db against
   the document's *pinned* form version. Returns {:document .. :form-raw ..}, or
   nil if there is no such document."
  [{:keys [forms documents session-manager]} doc-id]
  (when-let [doc (documents/get-document documents doc-id)]
    (let [form-raw (forms-compile/get-form-version forms (:form-id doc) (:form-version doc 1))]
      (session/ensure-document! session-manager doc-id form-raw (:db doc))
      {:document doc :form-raw form-raw})))

(defn rebase!
  "Opt-in (§15.1): move a document from its pinned form version up to the latest
   published version, applying the form's `:migrations` transforms in order,
   re-initialising the session against the new schema (so derived fields
   recompute), and persisting the migrated db + new pin + digest. The pre-rebase
   db is snapshotted under `:meta :pre-rebase`. No-op when already current.
   Returns the updated document record (or nil if missing)."
  [{:keys [forms documents session-manager] :as resources} doc-id]
  (when-let [doc (documents/get-document documents doc-id)]
    (let [target   (forms/latest-published forms (:form-id doc))
          form-new (forms-compile/get-form-version forms (:form-id doc) target)]
      (if (migrations/needs-migration? form-new (:form-version doc 1))
        (let [db' (migrations/migrate form-new (:form-version doc 1) (:db doc))]
          (session/ensure-document! session-manager doc-id form-new db')
          (documents/save-migration! documents doc-id
                                     (impl/db (session/current session-manager doc-id))
                                     target
                                     (forms/version-digest forms (:form-id doc) target))
          (documents/get-document documents doc-id))
        (do (ensure! resources doc-id)
            doc)))))
