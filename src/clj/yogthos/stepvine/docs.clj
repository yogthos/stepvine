(ns yogthos.stepvine.docs
  "Document service layer — ties together the form store, document store and
   session manager. Ensures the live editing session for a document instance
   exists, recreating it from the persisted db (e.g. after a restart) and
   migrating it forward if the form has been revised since."
  (:require
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.migrations :as migrations]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.editor.impl :as impl]))

(defn ensure!
  "Ensure the live session for `doc-id` exists, loading its persisted db. If the
   form has a newer :version than the document was last saved against, the db is
   migrated forward, re-initialized (so derived fields recompute), persisted, and
   the document's :form-version bumped. Returns {:document .. :form-raw ..}, or
   nil if there is no such document."
  [{:keys [forms documents session-manager]} doc-id]
  (when-let [doc (documents/get-document documents doc-id)]
    (let [form-raw (forms/get-form forms (:form-id doc))
          doc-v    (:form-version doc 1)
          upgrade? (migrations/needs-migration? form-raw doc-v)
          db       (if upgrade?
                     (migrations/migrate form-raw doc-v (:db doc))
                     (:db doc))]
      (session/ensure-document! session-manager doc-id form-raw db)
      (when upgrade?
        ;; persist the migrated + recomputed db and the new version
        (documents/save-migration! documents doc-id
                                   (impl/db (session/current session-manager doc-id))
                                   (migrations/current-version form-raw)))
      {:document doc :form-raw form-raw})))
