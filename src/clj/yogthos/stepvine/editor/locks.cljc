(ns yogthos.stepvine.editor.locks
  (:require
   [clojure.set :refer [union difference]]))

;; ==============================================================================
;; Locks
;; ==============================================================================

;; TODO: Use downstream instead of relatedness, validate that this is okay!
;;       This is okay, as long as we check for upstream conflicts in the same
;;       way as we do for parents.

;; Paths can be prevented from editing and/or locking by several means:
;;   1. They are being directly locked
;;   2. They are the child of a path which is locked
;;   3. They are affected by a rule from a directly locked path
;;   4. They are affected by a rule from a parent/child of a directly locked path
;;   5. They are the child of a path affected by #3 or 4


;; For now, locks are stored on a map of the form:
;;   {[:some :path] <lock?>}
;; But this doesn't need to be the case.
;; All lock lookup should use the `get-lock` function so that the underlying implementation can be changed.

;; locks have the following keys:
;; An active lock *must* have the following keys:
;; :locked-by <User>
;;     This key contains the user which locked the path, either directly or indirectly.
;;     The user must allow equality checks (e.g. a map).
;;     NOTE: support for a lock annotation/user equality fn may be added in the future
;;           to allow things like timestamps on lock and other lock metadata.
;; :primary <Boolean>
;;     This key designates whether this lock is explicit or derived.
;;     this is not used in any significant way right now, but could be used in the future.
;;     It may be used for things like preventing explicit changes to derived paths, or conditions in permissions.
;;     (e.g. a user can't edit a field directly, but can if it is via an event)
;; :origin-path <Path>
;;     This key specifies the path which is the primary lock associated.
;;     (i.e. the path which was requested that resulted in this lock.)
;;     If `:primary` is true, this is the path itself.
;;     NOTE: We currently prevent the explicit locking of two overlapping paths by the same user.
;;           If we want to support this, we should record any other paths which are origins for this lock.
;;           I find this doubtful, and it would likely be better practice to require root-level locks for this case.
;; :locking <Set[Path]>
;;     This key specifies the set of all paths which were locked when the `:origin-path` was locked.
;;     This is used when unlocking to ensure that locks aren't orphaned.
;; A lock map may have the following key, and the presence of this key doesn't necessarily indicate a locked state:
;; :locked-children (optional)
;;      This key is optional, and doesn't mean that the path is *actually* locked.
;;      The value must be a non-empty set of paths.
;;      If this key is present, it prevents this path from being locked.
;;      However, it does *not* prevent descendants from being locked.

;; A rough spec for a lock would be the following:
;; Can be one of:
;;   nil or {}                                     ;; i.e. unlocked
;;   {:locked-children #{path-spec}}               ;; i.e. not-lockable
;;   {(ds/opt :locked-children)  #{path-spec}      ;; i.e. locked
;;    :locked-by                 user-spec
;;    :origin-path               path-spec
;;    :primary                   boolean?
;;    :locking                   #{path-spec}}
;; NOTE: we *could* consider using spec matching for deciding on locked/not-lockable/unlocked

;; TODO: expose a acquire-lock-and-set! fn for fields which update instantaneously (e.g. toggles)


(defn get-lock
  "Abstracts over lock storage in session.
   Current implementation uses a map under `:yogthos.stepvine.editor.locks/locks`.
   This could be changed to facilitate storing multiple aspects of a path in the same data structure."
  [session id]
  ;; NOTE: If this changes, we should make sure to add update-lock! and set-lock! fns
  ;;       We also need to change all places where we touch the `::locks` map directly.
  (some-> session
          (get ::locks)
          (get id)))

(defn set-lock!
  [session id lock]
  (if (not-empty lock)
    (update session ::locks assoc id lock)
    (update session ::locks dissoc id)))

(defn update-lock!
  [session id f & args]
  (let [lock (apply f (get-lock session id) args)]
    (set-lock!
     session
     id
     (cond-> lock
       ;; Add other cleanup as neccessary
       (empty? (:locked-children lock)) (dissoc :locked-children)))))

(defn owns-lock?
  "Checks whether user owns this lock."
  [user lock]
  (= user (:locked-by lock)))

(defn get-derived-lock-ids
  "Given a function for relatedness, finds all ids transitively related to the current one.
   This includes relations via a parent.
   (i.e. a change to a child implies a change to a parent,
         which implies the relevance of the relationship,
         which implies a derived lock.)
  NOTE: The `related-fn`, if it returns a non-nil set, should include the id itself in the set."
  [{:keys [related-fn parents-fn]} id]
  (loop [locks #{}
         related #{id}]
    ;; (println "GET DERIVED LOCK IDS")
    ;; (println "RELATED")
    ;; (println related)
    ;; (println "LOCKS")
    ;; (println locks)
    (if (empty? (difference related locks))
      locks
      (recur (union related locks)
             (apply union #{}
                    (map (comp set related-fn) (into related (mapcat parents-fn related))))))))

(defn conflicting-locks
  "Returns a seq of locks which conflict with id via parenthood, beginning with a direct conflict if it exists."
  [{:keys [related-fn parents-fn] :as session} id]
  (let [primary-lock (not-empty (get-lock session id))       ;; The id itself must not be locked, and it must have no locked children
        related-locks (->>  (parents-fn id) ;; Parents may have locked children (i.e. siblings of id), but must not be actually locked

                           (map (partial get-lock session))
                           (filter :locked-by)
                           (not-empty))]
    (if primary-lock
      (cons primary-lock related-locks)
      related-locks)))

;;; Locking algorithm
;; Lock id itself.
;; Lock rule-related-ids (i.e. add to set of ids to lock and compute transitive relations).
;; find rule-having parents and lock them (i.e. add them to the set of ids to lock and compute transitive relations).
;; Add all relevant locks to lock map.
;; Annotate all locks' parents with 'locked-children'

(defn annotate-parent-ids!
  "Adds the id to each of it's parents' `:locked-children` sets"
  [{:keys [parents-fn] :as session} id]
  (reduce
   (fn [session parent]
     (update-lock! session parent update :locked-children (fnil conj #{}) id))
   session
   (parents-fn id)))

(defn lock-ids!
  "Locks each id to lock, including the total set of related locks, and the origin"
  [user session origin-id ids-to-lock]
  (reduce
   (fn lock-id-and-annotate-parents [session id]
     (->
      session
      (update-lock! id
                    assoc
                    :locked-by user
                    :primary (= id origin-id)
                    :origin-id origin-id
                    :locking ids-to-lock)
      (annotate-parent-ids! id)))
   session
   ids-to-lock))

;; ==============================================================================
;; Locking
;; We get an id to lock, and we need to find all ids which may be changed if
;; our id's value is changed. (i.e. downstream of id)

;; Computing downstream ids
;; - First, the id itself
;; - Then, any ids related to it by events/constraints (i.e. domino's notion of downstream)
;; - Then, any ids which are it's children
;; - Then,

;; ==============================================================================

(defn apply-lock!
  "Attempts to lock a id as user (user). Throws an error if there is a conflict."
  [user {:keys [related-fn parents-fn] :as session} id]
  (let [ids-to-lock (get-derived-lock-ids session id)]
    (if-some [[failed-id conflicting-lock]
              (some (fn [p] (when-some [conflict (first (conflicting-locks session p))]
                              [p conflict]))
                    ids-to-lock)]
      (throw (ex-info (str "Cannot lock " id " due to conflicting lock for related id: " failed-id)
                      {:id-to-lock id
                       :id-failed failed-id
                       :conflicting-lock conflicting-lock}))
      (lock-ids! user session id ids-to-lock))))

;; TODO use a reduce instead of comp potentially.
(defn unlock-ids! [{:keys [parents-fn] :as session} unlock-ids]
  (let [unlocking (set unlock-ids)]
    (-> session
        ;; First, dissociate all locks that are owned by this id
        (update ::locks (partial apply dissoc) unlocking)
        ;; Then, remove parent annotations.
        (update ::locks
                (apply comp
                       ;; Run an update function for each parent id of a locked id
                       (map
                        (fn [parent-id]
                          (fn [locks]
                            (let [lock (update
                                        (get locks parent-id)
                                        :locked-children
                                        #(not-empty
                                          (difference % unlocking)))]
                              (if-let [ks (not-empty
                                           (keep
                                            (fn [[k v]]
                                              (when (some? v)
                                                k))
                                            lock))]
                                (assoc locks parent-id (select-keys lock ks))
                                (dissoc locks parent-id)))))
                        ;; Find all parent ids which aren't locked ids themselves
                        (remove
                         unlocking
                         (distinct
                          (mapcat
                           parents-fn
                           unlocking)))))))))

(defn apply-unlock!
  [user session id]
  (let [{:keys [locked-by primary locking origin-id] :as lock}
           (get (::locks session) id)]
    (cond
      ;; Checks
      ;; Lock must exist
      (empty? lock)
      (throw (ex-info (str "No lock is present at the specified id!")
                      {:user user
                       :id id}))

      ;; Lock must be owned by user
      (not= user locked-by)
      (throw (ex-info (str "Only the lock's owner can unlock it.")
                      {:lock lock
                       :user user
                       :id id}))

      ;; Ensure that the primary lock is the one being unlocked
      (not primary)
      (do
        (recur user session origin-id))

      ;; Checks passed, do unlock
      :else
      (unlock-ids! session locking))))

(comment
  ;; TODO: Get parents lookup map from ctx instead of using this fn.
  (defn get-parent-paths
    "Returns a sequence of parent paths from the given path (includes the path itself).
  e.g.
  (get-parent-paths [:foo 0 :bar]) => '([:foo 0 :bar] [:foo 0] [:foo] [])"
    [path]
    (take-while some?
                (iterate
                 #(and (not-empty %) (vec (butlast %)))
                 path)))

  ;; TODO: this needs access to the parents fn from ctx
  (defn get-derived-lock-paths
    "Given a function for relatedness, finds all paths transitively related to the current one.
   This includes relations via a parent.
   (i.e. a change to a child implies a change to a parent,
         which implies the relevance of the relationship,
         which implies a derived lock.)
  NOTE: The `related-fn`, if it returns a non-nil set, should include the path itself in the set."
    [related-fn path]
    (loop [locks #{}
           related #{path}]
      (if (empty? (difference related locks))
        locks
        (recur (union related locks)
               (apply union #{}
                      (map related-fn (mapcat get-parent-paths related))))))))
