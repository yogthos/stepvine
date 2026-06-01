(ns yogthos.stepvine.documents
  "Document-instance store (`:store/documents`).

   A *document* is a saved instance of a form template:

     {:id <uuid> :form-id <form-id> :db {..persisted domino db..} :created-at <ms>}

   Distinct from the form (the template). Backed by a plain atom, or a duratom
   when a `:file` is configured (crash-safe persistence; v1 'database' for
   documents). On startup the persisted records are available immediately; the
   live editing session for a document is (re)created lazily from its `:db` when
   first loaded. Swappable to a real DB behind this API."
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [duratom.core :as duratom]
   [integrant.core :as ig])
  (:import
   [java.util UUID]))

(defn get-document [store id] (get @store id))

(defn list-documents
  "All document records, newest first."
  [store]
  (sort-by :created-at > (vals @store)))

(defn create!
  "Create a new empty document instance for a form, persist it, return the record.
   The creator (`created-by`) is the owner; `:shared` holds other user ids who
   may access it; `:form-version` records the form revision it was created against."
  ([store form-id] (create! store form-id nil 1))
  ([store form-id created-by] (create! store form-id created-by 1))
  ([store form-id created-by form-version]
   (let [doc {:id           (str (UUID/randomUUID))
              :form-id      (keyword form-id)
              :form-version form-version
              :created-by   created-by
              :shared       #{}
              :db           {}
              :created-at   (System/currentTimeMillis)}]
     (swap! store assoc (:id doc) doc)
     doc)))

(defn save-migration!
  "Persist a migrated db and bumped form-version for a document."
  [store id db form-version]
  (swap! store (fn [m]
                 (cond-> m
                   (contains? m id) (update id assoc :db db :form-version form-version)))))

;; --- Access control -------------------------------------------------------

(defn owner? [doc user-id] (= user-id (:created-by doc)))

(defn can-access?
  "True if `user-id` owns the document or it has been shared with them."
  [doc user-id]
  (boolean (and doc user-id
                (or (owner? doc user-id)
                    (contains? (:shared doc) user-id)))))

(defn accessible-by
  "Documents `user-id` may access (owned or shared), newest first."
  [store user-id]
  (filter #(can-access? % user-id) (list-documents store)))

(defn share!
  "Grant `user-id` access to a document."
  [store id user-id]
  (swap! store update-in [id :shared] (fnil conj #{}) user-id))

(defn save-db!
  "Persist the latest domino db for a document (no-op if it doesn't exist)."
  [store id db]
  (swap! store (fn [m] (cond-> m (contains? m id) (assoc-in [id :db] db)))))

(defn delete!
  [store id]
  (swap! store dissoc id))

(defmethod ig/init-key :store/documents
  [_ {:keys [file]}]
  (if file
    (do (io/make-parents file)
        (log/info "documents persisted to" file)
        (duratom/duratom :local-file :file-path file :init {}))
    (atom {})))
