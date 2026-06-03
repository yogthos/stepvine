(ns yogthos.stepvine.audit
  "Durable append-only audit log (PLAN.md §15.4).

   Every state-changing action records actor + before/after so a document's
   history is reconstructable. Two deliberate properties from the roadmap:
     - **before/after value diffs** + a **per-document** view (the reference logs
       only that *something* happened, with no diff and no per-doc index);
     - **never crash the caller** — a write is fire-and-forget and swallows its
       own failures to a dead-letter log (the reference calls System/exit on an
       audit failure, taking down every connected user).

   Backed by a duratom when a file is configured, an atom for tests; an append-only
   vector keyed query-side by :doc-id."
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [yogthos.stepvine.store :as store])
  (:import
   [java.util UUID]))

(defn store
  "A fresh in-memory audit store (for tests)."
  []
  (atom []))

(defn record!
  "Append an audit entry, stamping `:id` + `:at`. Returns the stored entry, or nil
   on failure (logged + dead-lettered, never thrown — auditing must not break the
   edit it records)."
  [store entry]
  (try
    (let [e (assoc entry :id (str (UUID/randomUUID)) :at (System/currentTimeMillis))]
      (swap! store conj e)
      e)
    (catch Throwable t
      (log/error t "audit write failed (dead-lettered):" (pr-str entry))
      nil)))

(defn for-document
  "Audit entries for a document, newest first."
  [store doc-id]
  (->> @store (filter #(= doc-id (:doc-id %))) (sort-by :at >)))

(defn entries
  "All audit entries (insertion order)."
  [store]
  @store)

(defmethod ig/init-key :store/audit
  [_ {:keys [file]}]
  (store/edn-file-store file {:init [] :label "audit log persisted to"}))
