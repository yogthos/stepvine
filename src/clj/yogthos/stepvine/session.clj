(ns yogthos.stepvine.session
  "Live editing sessions (`:session/manager`).

   Thin wrapper over the vendored editor layer (yogthos.stepvine.editor*). A session
   holds a Domino context for one open document; multiple connections share it.
   Applying a change recomputes derived fields (events) and reactions; the
   manager's on-update hook then (a) persists a snapshot to the document store
   and (b) broadcasts the changed signals to every connection over the Datastar
   hub — the reactive multi-user loop.

   Phase 3a applies changes lock-free; Phase 3b layers field locking on top
   (yogthos.stepvine.editor/lock! / unlock! / save-ids!)."
  (:require
   [integrant.core :as ig]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.hub :as hub]
   [clojure.string :as str]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.editor :as e]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.editor.locks :as locks])
  (:import
   [java.util UUID]))

;; --- Reading session state ------------------------------------------------

(defn- reaction-ids [session]
  (map :id (get-in (:form session) [:data :reactions])))

(defn snapshot
  "Serializable view of a session value: document db plus current reaction values."
  [session]
  {:db   (impl/db session)
   :rxns (into {} (map (fn [id] [id (impl/value session id)])) (reaction-ids session))})

;; --- Documents (persistent, shared sessions) ------------------------------

(defn ensure-document!
  "Get-or-create a live shared session for `doc-id` from `form-raw`. Idempotent
   and atomic, so concurrent first-accesses don't create duplicate sessions.
   Returns doc-id."
  [manager doc-id form-raw initial-db]
  (swap! (:sessions manager)
         (fn [m]
           (if (get m doc-id)
             m
             (assoc m doc-id (atom (impl/create-session form-raw initial-db))))))
  doc-id)

(defn current
  "Current session value, or throw if the document isn't open."
  [manager id]
  @(e/get-session-atom! manager id))

(defn current-maybe
  "Current session value, or nil if the document isn't open."
  [manager id]
  (some-> (e/get-session-atom manager id) deref))

(defn value
  [manager id field-id]
  (impl/value (current manager id) field-id))

(defn changed-ids
  "Field ids that changed in the document's last transact (domino's change report)."
  [manager id]
  (impl/changed-ids (current manager id)))

(defn emitted-effects
  "Effect intents the engine emitted during the document's last transact."
  [manager id]
  (impl/emitted-effects (current manager id)))

;; --- Applying changes -----------------------------------------------------

(defn apply-change!
  "Apply `[[id value] ...]` changes lock-free, recomputing via Domino and firing
   on-update (which broadcasts)."
  [manager id changes]
  (e/swap-session! manager id #(impl/apply-changes % changes))
  nil)

(defn apply-field-as!
  "Apply a single field change on behalf of `uid`. Saves under the user's lock
   when they hold it (the normal focus→edit path); rejects if another user holds
   it; otherwise applies lock-free (e.g. a debounce/blur race left no lock).
   Returns :ok or :rejected."
  [manager doc-id uid field-id value]
  (let [sess (current manager doc-id)
        lock (locks/get-lock sess field-id)]
    (cond
      (and lock (not (locks/owns-lock? uid lock)))
      :rejected

      (and lock (locks/owns-lock? uid lock))
      (do (e/save-ids! manager doc-id uid [[field-id value]]) :ok)

      :else
      (do (apply-change! manager doc-id [[field-id value]]) :ok))))

;; --- Collections (Domino subcontexts) -------------------------------------

(defn- new-index
  "A signal-safe collection index (hyphen-free, so it can appear verbatim in a
   Datastar signal name <coll>_<idx>_<field>)."
  []
  (str/replace (str (UUID/randomUUID)) "-" ""))

(defn add-item!
  "Add a new empty item to a collection; returns its index. Recomputes/broadcasts
   via on-update like any change."
  [manager doc-id coll-id]
  (let [idx (new-index)]
    (apply-change! manager doc-id [[[coll-id idx] {}]])
    idx))

(defn add-item-with!
  "Add a new collection item pre-populated from `values` (a {item-field -> value}
   map); returns its index. Used by the modal data-entry pattern (§ugx) to commit
   scratch fields into a row in one transact."
  [manager doc-id coll-id values]
  (let [idx (new-index)]
    (apply-change! manager doc-id [[[coll-id idx] (or values {})]])
    idx))

(defn remove-item!
  "Remove a collection item by index (setting it nil removes the child)."
  [manager doc-id coll-id idx]
  (apply-change! manager doc-id [[[coll-id idx] nil]]))

;; --- Nested collections (deep paths, §jj9) ---------------------------------
;; A nested item is addressed by a full path [coll idx coll idx …]; the engine
;; already stores nested data, so these are plain transacts at the deep id.

(defn add-deep-item!
  "Add an empty item at a nested collection path `[coll idx coll …]` (the path
   ends in a collection segment); returns the new index."
  [manager doc-id coll-path]
  (let [idx (new-index)]
    (apply-change! manager doc-id [[(conj (vec coll-path) idx) {}]])
    idx))

(defn remove-deep-item!
  "Remove a nested item at `[coll idx coll idx …]` (the path ends in an index)."
  [manager doc-id item-path]
  (apply-change! manager doc-id [[(vec item-path) nil]]))

(defn set-deep-item-field!
  "Set one field of a nested item: `item-path` is `[coll idx coll idx …]`."
  [manager doc-id item-path field-id value]
  (apply-change! manager doc-id [[(conj (vec item-path) (keyword field-id)) value]]))

(defn item-keys
  "Current item index keys of a collection, in db order. A session primitive used
   both here (clear-items!) and by the view-state layer (move-item!)."
  [manager doc-id coll-id]
  (vec (filter string? (keys (get (impl/db (current manager doc-id)) coll-id)))))

(defn clear-items!
  "Remove all items from a collection (and any stored view ordering)."
  [manager doc-id coll-id]
  (doseq [k (item-keys manager doc-id coll-id)]
    (apply-change! manager doc-id [[[coll-id k] nil]])))

;; Table view-state (sort/filter/page/row-order/column-overlay) is presentation,
;; not document data — it lives in `yogthos.stepvine.view-state`, built on the
;; `item-keys` primitive above and the session atom.

(defn set-item-field!
  "Set a single field of a collection item (vector id [coll idx field])."
  [manager doc-id coll-id idx field-id value]
  (apply-change! manager doc-id [[[coll-id idx field-id] value]]))

(defn apply-item-field-as!
  "Lock-aware set of a collection item field on behalf of `uid` (mirrors
   apply-field-as! for vector ids). Returns :ok or :rejected."
  [manager doc-id uid coll-id idx field-id value]
  (let [vid  [coll-id idx field-id]
        sess (current manager doc-id)
        lock (locks/get-lock sess vid)]
    (cond
      (and lock (not (locks/owns-lock? uid lock)))
      :rejected

      (and lock (locks/owns-lock? uid lock))
      (do (e/save-ids! manager doc-id uid [[vid value]]) :ok)

      :else
      (do (apply-change! manager doc-id [[vid value]]) :ok))))

;; --- Locking (reuses yogthos.stepvine.editor's lock manager) -----------------------

(defn lock-signal-map
  "The `locks` signal: {field-signal-name -> locker-uid-or-nil} across every
   top-level field AND every collection item field, computed from the session's
   lock map. Sent whole so cleared locks (nil) override prior values under
   JSON-merge-patch."
  [session]
  (let [top  (into {}
                   (map (fn [id] [(signals/signal-name id)
                                  (:locked-by (locks/get-lock session id))]))
                   (keys (:field-opts session)))
        item (into {}
                   (for [[coll-id {:keys [order field-opts]}] (signals/collections-data session)
                         idx order
                         fid (keys field-opts)]
                     [(str (signals/signal-name coll-id) "_" idx "_" (signals/signal-name fid))
                      (:locked-by (locks/get-lock session [coll-id idx fid]))]))]
    (merge top item)))

(defn broadcast-locks!
  [hub* manager doc-id]
  (when-let [s (current-maybe manager doc-id)]
    (hub/broadcast-signals! hub* doc-id {"locks" (lock-signal-map s)})))

(defn lock-field!
  "Acquire a lock on field-id for uid and broadcast the new lock state. Returns
   :ok, or :conflict if another user (transitively) holds it."
  [manager hub* doc-id uid field-id]
  (let [result (try (e/lock! manager doc-id uid field-id) :ok
                    (catch Exception _ :conflict))]
    (when (= :ok result)
      (broadcast-locks! hub* manager doc-id))
    result))

(defn unlock-field!
  "Release uid's lock on field-id (no-op if not held) and broadcast."
  [manager hub* doc-id uid field-id]
  (try (e/unlock! manager doc-id uid field-id)
       (catch Exception _ nil))
  (broadcast-locks! hub* manager doc-id)
  :ok)

(defn lock-item-field!
  "Acquire a lock on a collection item field (vector id) for uid; broadcast.
   Items are independent — locking one item's field doesn't block other items."
  [manager hub* doc-id uid coll-id idx field-id]
  (let [result (try (e/lock! manager doc-id uid [coll-id idx field-id]) :ok
                    (catch Exception _ :conflict))]
    (when (= :ok result)
      (broadcast-locks! hub* manager doc-id))
    result))

(defn unlock-item-field!
  [manager hub* doc-id uid coll-id idx field-id]
  (try (e/unlock! manager doc-id uid [coll-id idx field-id])
       (catch Exception _ nil))
  (broadcast-locks! hub* manager doc-id)
  :ok)

(defn release-user-locks!
  "Release all locks held by uid on a document (used on disconnect) and broadcast."
  [manager hub* doc-id uid]
  (when (and uid (current-maybe manager doc-id))
    (try (e/disconnect! manager doc-id uid)
         (catch Exception _ nil))
    (broadcast-locks! hub* manager doc-id)))

;; --- Component ------------------------------------------------------------

(defn- broadcast-changes!
  "Diff old vs new session signals and push only the changed ones (plus any
   `extra` system signals, e.g. the bumped :rev) to all connections."
  [hub* id old new extra]
  (let [old-sigs (signals/session->signal-map old)
        new-sigs (signals/session->signal-map new)
        delta    (merge (into {} (remove (fn [[k v]] (= v (get old-sigs k)))) new-sigs)
                        extra)]
    (when (seq delta)
      (hub/broadcast-signals! hub* id delta))))

(defmethod ig/init-key :session/manager
  [_ {:keys [documents hub]}]
  (e/session-manager
   {:on-update
    (fn [id old new]
      ;; persist the latest db back into the document record (if one exists); the
      ;; save bumps :rev, which we broadcast so every client's $rev stays current
      (let [saved (when documents
                    (documents/save-db! documents id (impl/db new)))]
        (when hub
          (broadcast-changes! hub id old new (when saved {"rev" (:rev saved)})))))}))
