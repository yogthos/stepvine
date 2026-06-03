(ns yogthos.stepvine.editor
  "Multi-user session manager over the Domino editor seam (`editor.impl`/`.data`)
   and the field-lock manager (`editor.locks`). A *session manager* holds a map of
   `session-id -> (atom session)` plus an `:on-update` hook; `swap-session!` is the
   one mutation primitive (serialized per session so apply + persist + broadcast
   share one order). Public surface used by `yogthos.stepvine.session`:
   `session-manager`, `get-session-atom`/`!`, `swap-session!`, `lock!`/`unlock!`,
   `save-ids!`, `disconnect!`."
  (:require [domino.core :as d]
            [clojure.set :refer [union]]
            [yogthos.stepvine.editor.impl :as impl]
            [yogthos.stepvine.editor.util :as util]
            [yogthos.stepvine.editor.locks :as locks]
            [yogthos.stepvine.editor.data :as data]))

(defn session-manager
  "Given a map of options, creates a construct for managing document editing sessions."
  [{:keys [id-fn on-update wrap-update]
    :or {id-fn (fn [_ _] (util/random-uuid))}}]
  {:id-fn id-fn
   :wrap-update wrap-update
   :on-update on-update
   :sessions (atom {})})

(defn get-session-atom [manager session-id]
  (get @(:sessions manager) session-id))

(defn get-session-atom! [manager session-id]
  (or (get-session-atom manager session-id)
      (throw (ex-info "Session doesn't exist!" {:session-id session-id
                                                :sessions (keys @(:sessions manager))}))))

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

(defn lock! [manager session-id user id]
  ;; NOTE: throws on conflict — add a pre-check/handler if undesired.
  (swap-session! manager session-id #(locks/apply-lock! user % id)))

(defn unlock! [manager session-id user id]
  (swap-session! manager session-id #(locks/apply-unlock! user % id)))

(defn- save-ids [session user changes]
  (if-let [conflicts
           (->> changes
                (mapcat
                 (fn [change]
                   (cond
                     (map? change)            (keys change)
                     (= ::d/set (first change)) (keys (second change))
                     (#{::d/set-value ::d/remove-value ::d/update-child ::d/remove-child}
                      (first change))         [(second change)]
                     :else                    [(first change)])))
                (keep
                 (fn [id]
                   (let [l (locks/get-lock session id)]
                     (when-not (locks/owns-lock? user l)
                       [id l]))))
                not-empty)]
    (throw (ex-info "User must own locks on all ids being changed!"
                    {:error-id ::locking-error :user user
                     :changes changes :conflicts (into {} conflicts)}))
    (let [result (impl/apply-changes session changes)
          {:keys [status] :as report} (data/get-tx-report (::data/ctx result))]
      (if (= status :complete)
        (assoc result ::latest-editor user)
        (throw (ex-info "Error transacting changes!"
                        {:error-id ::transaction-error :user user
                         :changes changes :report report}))))))

(defn save-ids!
  "Apply `changes` ([id value] pairs) on behalf of `user`; throws unless the user
   owns the locks on every changed id."
  [manager session-id user changes]
  (swap-session! manager session-id #'save-ids user changes))

(defn- clear-locks [session user]
  (let [ids (apply union #{}
                   (->> (vals (::locks/locks session))
                        (filter (partial locks/owns-lock? user))
                        (map :locking)))]
    (locks/unlock-ids! session ids)))

(defn disconnect! [manager session-id user]
  (swap-session! manager session-id
                 #(-> %
                      (clear-locks user)
                      (update :connections (fnil disj #{}) user))))
