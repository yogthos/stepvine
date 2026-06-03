(ns yogthos.stepvine.documents
  "Document-instance store (`:store/documents`).

   A *document* is a saved instance of a form template, carrying its pinned form
   version, lifecycle `:status`, a `:rev` concurrency token, the persisted domino
   `:db`, and a system `:meta` map.

   The store is **pluggable behind a `DocStore` protocol** (§15.13): an
   atom/duratom (the default — in-memory or a crash-safe EDN file) *and* an
   embedded **SQLite** query backend (`SqlStore`) that keeps the document EDN in a
   `doc` column with denormalised, **indexed** `owner`/`form_id`/`status`/
   `created_at` columns for real queries. The public fns below are thin wrappers
   over the protocol, so every caller is backend-agnostic. Plain atoms satisfy
   the protocol directly, so existing tests pass an `(atom {})` unchanged."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [duratom.core :as duratom]
   [integrant.core :as ig]
   [next.jdbc :as jdbc])
  (:import
   [java.util UUID]))

;; --- Store protocol -------------------------------------------------------

(defprotocol DocStore
  (-fetch     [s id]    "The document map for `id`, or nil.")
  (-all       [s]       "All document maps.")
  (-put!      [s doc]   "Insert/replace a whole document; returns it.")
  (-transact! [s id f]  "Apply `f` to the existing document `id` (no-op if absent); returns the new doc or nil.")
  (-delete!   [s id]    "Remove document `id`.")
  (-query     [s crit]  "Documents matching `crit` {:owner :status} via the backend's index."))

;; Atoms + duratoms (both implement IAtom) are the default in-memory/EDN-file store.
(extend-protocol DocStore
  clojure.lang.IAtom
  (-fetch     [s id]   (get @s id))
  (-all       [s]      (vals @s))
  (-put!      [s doc]  (swap! s assoc (:id doc) doc) doc)
  (-transact! [s id f] (get (swap! s (fn [m] (cond-> m (contains? m id) (update id f)))) id))
  (-delete!   [s id]   (swap! s dissoc id) nil)
  (-query     [s {:keys [owner status]}]
    (filter (fn [d] (and (or (nil? owner)  (= owner  (:owner d)))
                         (or (nil? status) (= status (:status d)))))
            (vals @s))))

;; --- SQLite backend -------------------------------------------------------

(def ^:private schema
  ["create table if not exists documents
     (id text primary key, owner text, form_id text, status text,
      created_at integer, doc text not null)"
   "create index if not exists idx_documents_owner  on documents(owner)"
   "create index if not exists idx_documents_status on documents(status)"])

(defn- row->doc [row] (some-> (:documents/doc row) edn/read-string))

(defn- upsert! [ds doc]
  (jdbc/execute!
   ds ["insert or replace into documents(id,owner,form_id,status,created_at,doc)
        values(?,?,?,?,?,?)"
       (:id doc) (some-> (:owner doc) str) (some-> (:form-id doc) name)
       (some-> (:status doc) name) (:created-at doc) (pr-str doc)]))

(defrecord SqlStore [ds]
  DocStore
  (-fetch [_ id]
    (row->doc (jdbc/execute-one! ds ["select doc from documents where id=?" id])))
  (-all [_]
    (mapv row->doc (jdbc/execute! ds ["select doc from documents"])))
  (-put! [_ doc] (upsert! ds doc) doc)
  (-transact! [s id f]
    (when-let [doc (-fetch s id)] (let [doc' (f doc)] (upsert! ds doc') doc')))
  (-delete! [_ id] (jdbc/execute! ds ["delete from documents where id=?" id]) nil)
  (-query [_ {:keys [owner status]}]
    ;; indexed WHERE on the denormalised columns
    (let [clauses (cond-> [] owner (conj "owner=?") status (conj "status=?"))
          args    (cond-> [] owner (conj (str owner)) status (conj (name status)))
          where   (if (seq clauses) (str " where " (str/join " and " clauses)) "")]
      (mapv row->doc (jdbc/execute! ds (into [(str "select doc from documents" where)] args))))))

;; --- Public API (backend-agnostic) ----------------------------------------

(defn get-document [store id] (-fetch store id))

(defn list-documents
  "All document records, newest first."
  [store]
  (sort-by :created-at > (-all store)))

(defn create!
  "Create a new empty document instance for a form, persist it, return the record.
   Pins the exact `[:form-version :form-digest]` it is created against (§15.2). The
   creator owns it; `:shared` holds other user ids who may access it. The record
   carries lifecycle state (`:status`), an optimistic-concurrency `:rev`, and a
   `:meta` map of system fields clients can never write directly."
  ([store form-id] (create! store form-id {}))
  ([store form-id {:keys [created-by form-version form-digest]}]
   (let [now (System/currentTimeMillis)
         doc {:id           (str (UUID/randomUUID))
              :form-id      (keyword form-id)
              :form-version (or form-version 1)
              :form-digest  form-digest
              :created-by   created-by         ; retained for existing ACL checks
              :owner        created-by
              :shared       #{}
              :status       :in-progress
              :db           {}
              :rev          0
              :meta         {:created-at  now :created-by  created-by
                             :modified-at now :modified-by created-by
                             :submitted-views #{} :approvals [] :deleted? false}
              :created-at   now}]
     (-put! store doc)
     doc)))

(defn save-migration!
  "Persist a rebased db, bumped form-version and new digest for a document (§15.1
   opt-in rebase). Snapshots the pre-rebase db under `:meta :pre-rebase`."
  [store id db form-version form-digest]
  (-transact! store id
              (fn [doc]
                (-> doc
                    (assoc-in [:meta :pre-rebase] {:version (:form-version doc) :db (:db doc)})
                    (assoc :db db :form-version form-version :form-digest form-digest)))))

;; --- Access control -------------------------------------------------------

(def locked-statuses
  "Statuses in which a document is read-only — edits are rejected (§15.5)."
  #{:submitted :completed :cancelled})

(defn locked?
  "True if the document is in a read-only (finalized) status."
  [doc]
  (boolean (locked-statuses (:status doc))))

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

(defn find-by
  "Documents matching `criteria` {:owner :status} via the backend's index."
  [store criteria]
  (-query store criteria))

;; --- Content search (§j00) -------------------------------------------------
;; A simple, backend-agnostic content search over the field values stored in a
;; document's domino `:db` (plus its form id). It is ALWAYS layered on top of
;; `accessible-by`, so a user can only ever search documents they may access —
;; the auth scope is structural, not an afterthought. (A SQLite JSON1/FTS5 index
;; can replace the in-Clojure scan later without changing this contract.)

(defn document-text
  "Lower-cased searchable text for a document: every string/number value in its
   `:db` (top-level + collection + nested-collection fields) plus the form id."
  [doc]
  (let [vals (volatile! (transient [(name (:form-id doc))]))]
    (walk/postwalk
     (fn [x] (when (or (string? x) (number? x)) (vswap! vals conj! (str x))) x)
     (:db doc))
    (str/lower-case (str/join " " (persistent! @vals)))))

(defn matches-query?
  "True when `doc`'s searchable text contains `q` (case-insensitive). A blank
   query matches everything."
  [doc q]
  (let [q (str/lower-case (str/trim (str q)))]
    (or (str/blank? q)
        (str/includes? (document-text doc) q))))

(defn search-accessible
  "Documents `user-id` may access whose content matches `query`, newest first.
   Auth-scoped by construction — only `accessible-by` documents are considered."
  [store user-id query]
  (->> (accessible-by store user-id)
       (filter #(matches-query? % query))))

(defn share!
  "Grant `user-id` access to a document."
  [store id user-id]
  (-transact! store id (fn [doc] (update doc :shared (fnil conj #{}) user-id))))

(defn save-db!
  "Persist the latest domino db for a document and bump its optimistic-concurrency
   `:rev` + `:meta :modified-at` (no-op if it doesn't exist)."
  [store id db]
  (-transact! store id
              (fn [doc]
                (-> doc
                    (assoc :db db)
                    (update :rev (fnil inc 0))
                    (assoc-in [:meta :modified-at] (System/currentTimeMillis))))))

;; --- Submission / approval (§15.5) ----------------------------------------

(defn submitted-for?
  "True if `view-id` has been submitted on this document."
  [doc view-id]
  (boolean (contains? (set (get-in doc [:meta :submitted-views])) view-id)))

(defn submit!
  "Finalize a view: record an append-only approval + an immutable snapshot, add
   the view to `:submitted-views`, set `:status :submitted`, bump `:rev`."
  [store id view-id uid snapshot]
  (-transact! store id
              (fn [doc]
                (let [now (System/currentTimeMillis)]
                  (-> doc
                      (update-in [:meta :submitted-views] (fnil conj #{}) view-id)
                      (update-in [:meta :approvals] (fnil conj []) {:view view-id :by uid :at now})
                      (update-in [:meta :reports] (fnil conj [])
                                 {:view view-id :by uid :at now
                                  :form-version (:form-version doc) :snapshot snapshot})
                      (assoc :status :submitted)
                      (update :rev (fnil inc 0)))))))

(defn revise!
  "Re-open a submitted view (keeps the approval log — append-only). Returns the
   document to `:in-progress` only when no submitted views remain."
  [store id view-id]
  (-transact! store id
              (fn [doc]
                (let [views (disj (set (get-in doc [:meta :submitted-views])) view-id)]
                  (-> doc
                      (assoc-in [:meta :submitted-views] views)
                      (assoc :status (if (seq views) :submitted :in-progress))
                      (update :rev (fnil inc 0)))))))

;; --- Workflow state machine (§15.10) --------------------------------------

(defn workflow-state
  "The document's current workflow state, or `default` (its form's :initial) when
   unset."
  [doc default]
  (get-in doc [:meta :workflow :state] default))

(defn set-workflow-state!
  "Move the document into workflow state `state`, appending to its transition
   history and bumping :rev. `locked?` marks the state read-only (mirrored into
   `:status` so the existing edit guard applies)."
  [store id state locked? by]
  (-transact! store id
              (fn [doc]
                (-> doc
                    (assoc-in [:meta :workflow :state] state)
                    (update-in [:meta :workflow :history] (fnil conj [])
                               {:state state :by by :at (System/currentTimeMillis)})
                    (assoc :status (if locked? :submitted :in-progress))
                    (update :rev (fnil inc 0))))))

(defn assignee
  "The user-id this document is currently assigned to, or nil."
  [doc] (get-in doc [:meta :assignee]))

(defn assign!
  "Route the document to user `user-id` (nil clears the assignment), recording it
   in `[:meta :assignee]` + the assignment history, bumping :rev."
  [store id user-id by]
  (-transact! store id
              (fn [doc]
                (-> doc
                    (assoc-in [:meta :assignee] user-id)
                    (update-in [:meta :assignments] (fnil conj [])
                               {:to user-id :by by :at (System/currentTimeMillis)})
                    (update :rev (fnil inc 0))))))

(defn update-meta!
  "Persist a value at `[:meta & path]` (workflow step directive), bumping :rev."
  [store id path value]
  (-transact! store id
              (fn [doc] (-> doc
                            (assoc-in (into [:meta] path) value)
                            (update :rev (fnil inc 0))))))

(defn delete!
  [store id]
  (-delete! store id))

;; --- Component ------------------------------------------------------------

(defn- sql-store
  "An embedded SQLite-backed document store at `db-file` (the schema is created on
   first use)."
  [db-file]
  (io/make-parents db-file)
  (let [ds (jdbc/get-datasource (str "jdbc:sqlite:" db-file))]
    (doseq [stmt schema] (jdbc/execute! ds [stmt]))
    (log/info "documents backed by SQLite at" db-file)
    (->SqlStore ds)))

(defmethod ig/init-key :store/documents
  [_ {:keys [backend file db-file]}]
  (case (or backend :atom)
    :sql (sql-store (or db-file "data/documents.db"))
    (if file
      (do (io/make-parents file)
          (log/info "documents persisted to" file)
          (duratom/duratom :local-file :file-path file :init {}))
      (atom {}))))
