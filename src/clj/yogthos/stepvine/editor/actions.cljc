(ns yogthos.stepvine.editor.actions)


;; ::pending key on `session` will look like the following:

#_{:action-id #{{:user   {:userid "foo" :etc "..."}
                 :params {:some "params" :etc "..."}
                 :locks  #{:field-id "..."}}}}

(defn filter-action-params [session action-id params]
  (let [{:keys [unregistered-params
                permitted-params
                elide-params]
         :as action-config} (-> (::rules session)
                                (get action-id))]
    (if elide-params
      ::params-elided
      (cond-> params
        unregistered-params ((partial apply dissoc) unregistered-params)
        permitted-params    (select-keys permitted-params)))))

(defn is-sole-user? [session user]
  (= (:connections session) #{user}))

(defn action-is-permitted? [session user action-id params]

  (if-some [rules (some-> session
                          ::rules
                          (get action-id))]
    ;; TODO: add type-based dispatch, configuration, and composition
    ;; NOTE: We are assuming `:sole-user` for any non-nil `rules` entry.
    (and
     (is-sole-user? session user)
     (every? (comp empty? val) (::pending session)))
    (throw (ex-info "No action defined for specified action id!"
                    {:action-id action-id
                     :defined-actions (keys (::rules session))}))))

(defn locks-for-action [session user action-id params]
  ;; TODO: get locks when needed!
  (or (some-> session
              ::rules
              (get action-id)
              :locks)))
