(ns yogthos.stepvine.docs
  "Document service layer — ties together the form store, document store and
   session manager.

   A document is **pinned** to the exact form version it was created against
   (§15.1): `ensure!` loads that frozen version from the archive and recreates the
   live session from the persisted db — it never silently migrates a document onto
   a newer form. Moving a document forward is the explicit, opt-in `rebase!`."
  (:require
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.migrations :as migrations]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.editor.impl :as impl]))

(defn ensure!
  "Ensure the live session for `doc-id` exists, loading its persisted db against
   the document's *pinned* form version. Returns {:document .. :form-raw ..}, or
   nil if there is no such document."
  [{:keys [forms documents session-manager]} doc-id]
  (when-let [doc (documents/get-document documents doc-id)]
    (let [form-raw (forms/get-form-version forms (:form-id doc) (:form-version doc 1))]
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
          form-new (forms/get-form-version forms (:form-id doc) target)]
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
