(ns yogthos.stepvine.editor
  (:require [domino.core :as d]
            [sci.core :as sci]
            [clojure.pprint :refer [pprint]]
            [clojure.set :refer [union difference]]
            [yogthos.stepvine.editor.impl :as impl]
            [yogthos.stepvine.editor.util :as util]
            [yogthos.stepvine.editor.locks :as locks]
            [yogthos.stepvine.editor.actions :as actions]
            [yogthos.stepvine.editor.data :as data]))

;; This is the namespace where locks, session management, and rules evaluation will come together.
;; Implement requisite namespaces first!

;; ==============================================================================

;; _TODO_

;; - DONE Update Relatedness check
;; - DONE Add parent/child relationships for lock computation
;; - Add events which cross context boundaries to domino
;; - Add nested locking contexts
;; - DONE Update change-ids! to use new version of domino's transact fn (i.e. ids)
;; - Add accessor helper fns which do dereffing and unwrapping
;; - Add generic guard to check that user is connected
;; - Add connect fn which will register user as editing/viewing doc.
;; - Enhance user registry to track activity etc, leave open ended for enhancements in application layer

;; ==============================================================================


;; TODO: pull session related stuff into own namespace.
;;       expose clean API from core namespace.
;;       ensure alignment with usage pattern.

;; TODO: Create namespace(s) for parsing model and views.
;;       Ensure that unparsed version is kept around esp. for builder.

;; TODO create protocol for session actions


(defn session-manager
  "Given a map of options, creates a construct for managing document editing sessions."
  [{:keys [id-fn on-update wrap-update]
    :or {id-fn
         (fn [_ _]
           (util/random-uuid))}
    :as opts}]
  (let [sessions (atom {})]
    {:id-fn id-fn
     :wrap-update wrap-update
     :on-update on-update
     :sessions sessions}))

(defn get-session-atom [manager session-id]
  (get @(:sessions manager) session-id))

(defn get-session-atom! [manager session-id]
  (or (get-session-atom manager session-id)
      (throw (ex-info "Session doesn't exist!" {:session-id session-id
                                                :sessions (keys @(:sessions manager))}))))

(defn create-session!
  "given a manager, a form, and an initial-db state, it will create a session map (e.g. {:form <form-declaration> ::data/ctx <context-around-initial-db> ::locks/locks {}}) and associate it onto the manager's `:sessions` atom under the ID generated from the form and db.
  Id fn defaults to a random UUID.
  If sessions are to be coordinated, the id should be derived from the initial-db and/or the form.
  The `:id-fn` is set when the session manager is initialized by `yogthos.stepvine.editor/session-manager`"
  [manager form initial-db]
  ;; Generate ID and session outside of swap! in case there are side-effects
  (let [session-id ((:id-fn manager
                            (fn [_ _]
                              (println "Fallback id fn: UUID")
                              (util/random-uuid)))
                    form
                    initial-db)
        new-session (atom (impl/create-session form initial-db))]
    ;; TODO: either account for collisions here, or wrap in a function that provides upsert instead of overwrite
    (swap! (:sessions manager) assoc session-id new-session)
    session-id))

(defn swap-session! [manager session-id f & args]
  (let [f'    (if-some [wrap (:wrap-update manager)]
                (wrap f)
                f)
        satom (get-session-atom! manager session-id)]
    ;; Serialize the state swap AND its on-update side-effects (persist + SSE
    ;; broadcast) per session. on-update runs OUTSIDE swap-vals!, so without this
    ;; lock two concurrent edits to one document can apply in order T1→T2 but
    ;; broadcast T2→T1 — a stale derived value (e.g. a pre-cascade total) then
    ;; arrives last and wins on the client. Locking the whole unit makes apply,
    ;; persist and broadcast order identical, so the latest value always wins.
    ;; (In CLJS `locking` just runs the body — JS is single-threaded.)
    (locking satom
      (let [[old new] (apply swap-vals! satom f' args)]
        (when-some [on-update (:on-update manager)]
          (try
            (on-update session-id old new)
            (catch #?(:clj Throwable
                      :cljs js/Error) e
              (println "UPDATE FAILED!!!")
              (throw e))))
        new))))

(defn connect! [manager session-id connection]
  (swap-session! manager session-id
                 #(update % :connections
                          (fnil conj #{}) connection)))

(defn lock! [manager session-id user id]
  ;; NOTE: this will throw an error on conflict.
  ;;       add pre-check or error handling if this is undesired
  (swap-session! manager session-id #(locks/apply-lock! user % id)))

(defn unlock! [manager session-id user id]
  ;; NOTE: this will throw an error on conflict.
  ;;       add pre-check or error handling if this is undesired
  (swap-session! manager session-id #(locks/apply-unlock! user % id)))

(defn- save-ids [session user changes]
  (if-let [conflicts
           (->> changes
                (mapcat
                 (fn [change]
                   (cond
                     (map? change)
                     (keys change)

                     (= ::d/set (first change))
                     (keys (second change))

                     (#{::d/set-value
                        ::d/remove-value
                        ::d/update-child
                        ::d/remove-child}
                      (first change))
                     [(second change)]

                     :else
                     [(first change)])))
                (keep
                 (fn [id]
                   (let [l (locks/get-lock session id)]
                     (when-not (locks/owns-lock? user l)
                       [id l]))))
                not-empty)]
    (throw (ex-info "User must own locks on all ids being changed!"
                    {:error-id ::locking-error
                     :user user
                     :changes changes
                     :conflicts (into {} conflicts)}))
    (let [result (impl/apply-changes session changes)
          {:keys [status] :as report} (data/get-tx-report (::data/ctx result))]
      (if (= status :complete)
        (assoc result ::latest-editor user)
        (throw (ex-info "Error transacting changes!"
                        {:error-id ::transaction-error
                         :user user
                         :changes changes
                         :report report}))))))

(defn save-ids! [manager session-id user changes]
  ;; NOTE: this will throw an error if the user doesn't have the requisite locks.

  ;; TODO: the editor currently only supports changes of the form:
  ;;       [<some-id> <value>]
  ;;       Should add support for the following:
  ;;       [::d/set-value <id> <value>]
  ;;       [::d/remove-value <id> <value>]
  ;;       [::d/update-child [<id> & <ids>] & <changes>]
  ;;       [::d/remove-child [<id> & <ids>]]
  ;;       [::d/set {<id> <value>}]
  ;;       {<id> <value>}
  ;;       etc...
  ;;       This could potentially leverage domino change parsing...

  ;; TODO: Do a final check on session ctx tx-report for ids which aren't locked.
  ;;       or at least rigorously test to ensure that locking logic is consistent
  ;;       with actual change output.

  ;; TODO: test the absolute shit out of locks.
  ;;       Check where assumptions break and fail fast if they do.

  ;; TODO: Reference db from context in top level, or add getter fn for doc
  (swap-session! manager session-id #'save-ids user changes))

(defn- lock-and-save-ids [session user ids changes]
  (as-> session $
    (reduce
     (partial locks/apply-lock! user)
     $
     ids)
    (save-ids $ user changes)
    (reduce
     (partial locks/apply-unlock! user)
     $
     (reverse ids))))

(defn lock-and-save-ids! [manager session-id user ids changes]
  ;; NOTE: ids must be explicitly locked.
  ;;       Potentially add a step to compute ids to lock from changes?
  ;;       Alternatively, change lock checking logic on save to only ensure lock isn't owned?
  (swap-session! manager session-id lock-and-save-ids user ids changes))

;; TODO: Add user tracking!
;;       (i.e. enforce that a user must be on the session in order to edit/lock)
;;       This will likely simplify auth logic and prevent oversights
;;       Additionally, if we keep track of a user's locks we don't have to
;;       iterate over the locks map to boot them.

(defn- clear-locks [session user]
  (let [ids
        (apply union #{}
               (->> (vals (::locks/locks session))
                    (filter (partial locks/owns-lock? user))
                    (map :locking)))]
    (locks/unlock-ids! session ids)))

(defn disconnect! [manager session-id user]
  (swap-session! manager session-id
         ;; TODO: Make this more performant once user tracking is implemented
         #(-> %
              (clear-locks user)
              (update :connections (fnil disj #{}) user))))




(defn- mark-action-pending [session user action-id full-params]
  (let [params   (actions/filter-action-params session action-id full-params)
        lock-ids (actions/locks-for-action session user action-id params)]
    (if (actions/action-is-permitted? session user action-id full-params)
      (-> (reduce
           (partial locks/apply-lock! user)
           session
           lock-ids)
          (update ::actions/pending
                  update action-id
                  (fnil conj #{})
                  {:action action-id
                   :user   user
                   :params params
                   :locks  lock-ids}))
      (throw (ex-info "Action is not permitted!"
                      {:action-id action-id
                       :params    params})))))

(defn mark-action-pending! [manager session-id user action-id params]
  (swap-session! manager session-id mark-action-pending user action-id params))

(defn- clear-action-pending [session user action-id full-params]
  (let [params (actions/filter-action-params session action-id full-params)]
    (if-some [{:keys [locks] :as axn} (some (fn [axn]
                                              (when (and (= user   (:user axn))
                                                         (= params (:params axn)))
                                                axn))
                                            (get (::actions/pending session) action-id))]
      (-> (reduce
           (partial locks/apply-unlock! user)
           session
           locks)
          (update ::actions/pending (fn [actions]
                                      (let [axns (disj (get actions action-id) axn)]
                                        (if (empty? axns)
                                          (dissoc actions action-id)
                                          (assoc  actions action-id axns))))))
      (throw (ex-info "Action is not pending."
                      {:action action-id
                       :params params})))))

(defn clear-action-pending! [manager session-id user action-id params]
  (swap-session! manager session-id clear-action-pending user action-id params))





;; ==============================================================================
;; NOTES
;; ==============================================================================

;; General Thoughts/Concerns:
;; Extensibility
;; - Provide the ability to wrap `transact` in a middleware/interceptor-style HOF
;;   this would facilitate all sorts of enhancements:
;;   - Custom auth logic
;;   - Coercion/validation pre/post tx
;;   - Arbitrary user-level enhancement
;; - Provide more generic types of changes (i.e. beyond [path value] pairs)
;;   (e.g. delete, permute, add, compare-and-swap, transform...)
;;
;; Pragmatism
;; - Is the purity of domino explicitly neccessary?
;;   What's wrong with compiling the form into an atom w/ tracks?
;;   (I tried to do this in a pure manner, but it's kinda painful to enhance)
;;   It may be faster, easier to reason about, and easier to work with if we
;;   expose dereffables...
;;   This has the added benefit of being closer to what reagent expects

;; Use cases for Model:
;; leaf node
;; - Specify that it cannot be set explicitly (i.e. computed or pre-populated)
;;   (e.g. MRN for a patient from external system, Age from birth-date, BMI...)
;; - Specify computation fn (note, this could also be done later if properly noted)
;;   (e.g. calculate BMI in place, format summary string, etc.)
;; - Type checking, Defaults,  Validation rules, and optional coercion/resolution
;;   (e.g. Strip dashes from phone number, Capitalize text, Auto-correct med names...)
;;   NOTE: some use cases may be better served by rules/events/relationships
;; - Change restrictions based on auth (e.g. only physician can change diagnosis)
;; - Global flags for use by widgets
;;
;; branch node
;; - Change restrictions based on auth (e.g. role by section)
;; - Persistence/enforcement strategy for children
;;   (e.g. DON'T save partially complete meds. Ensure it's fully valid.)
;; - cross-child constraints/computations
;;   (e.g. admission date can't be after discharge date)
;; - Inherited flags/configuration
;; - Nested forms/editing contexts (i.e. embedding)
;; - Collections/Lists
;;   (e.g. List of meds for a patient, list of emergency contacts)
;;   - Synchronized vs. Local sorting
;;   - Lazy loading (Provide SQL/DB backing w/ relational reference to support large docs)
;;   - Locking strategy for re-ordering, editing, adding, deleting children.
;;   - Special component for adding vs. editing.
;;   - Permissions for adding, deleting, ordering, editing.
;;   - Categorizing/Selecting a primary element.
;;   - This featureset is a can of worms, but has a lot of potential. do it right.

;; Use cases for Events:
;; - Specify rules which are too cross-cutting/complex to fit in model
;; - In theory anything handled here could just be on the root context
;;   Conversely, everything above could be compiled into events.
;;   (see `Model > branch node` notes above)

;; Use cases for Hooks and/or effects:
;; - Interact with the outside world based on the editing of the document
;; - Interact with parent contexts if this pattern is used.
;; - Effects are specified in the `:data` map, and should be triggered by/listened to by `:hooks`


;; Uses/Concerns which don't fit above:

;; ==============================================================================
;; SAMPLE DATA
;; ==============================================================================

;; TODO: pull samples into dedicated namespace
