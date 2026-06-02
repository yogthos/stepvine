(ns yogthos.stepvine.web.queue
  "Work queues (parity stepvine-cfp): a workflowed form's documents grouped by
   workflow state, ACROSS owners, for the form's team (its role holders) — so a
   reviewer can find and open the documents waiting on them. Complements the
   owner-scoped landing. Opening a queued document relies on the team-member rule
   in `wrap-doc-access`."
  (:require
   [ring.util.response :as resp]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.layout :as layout]))

(def ^:private styles
  (str "table{border-collapse:collapse;width:100%;margin:.25rem 0 1rem} "
       "td,th{border:1px solid #e5e7eb;padding:.4rem .5rem;text-align:left} "
       ".muted{color:#6b7280} .sv-queues{line-height:1.9} "
       ".sv-state{display:inline-block;font-size:.7rem;font-weight:700;text-transform:uppercase;"
       "letter-spacing:.02em;background:#eef2ff;color:#3730a3;padding:.1rem .45rem;border-radius:.25rem}"))

(defn- workflowed? [form] (some? (:workflow form)))

(defn- docs-for-form [documents-store form-id]
  (filter #(= (name form-id) (some-> (:form-id %) name))
          (documents/list-documents documents-store)))

(defn my-queues
  "Workflowed forms `user` is a team member of, with their workflow."
  [forms-store access-store user]
  (for [id (forms/list-forms forms-store)
        :let [f (forms/get-form forms-store id)]
        :when (and (workflowed? f) (access/team-member? access-store user id))]
    {:id id :title (:title f) :workflow (:workflow f)}))

(defn- page [user title crumbs & body]
  (-> (resp/response
       (apply layout/page {:user user :title title :crumbs crumbs :head [:style styles]} body))
      (resp/content-type "text/html")))

(defn index
  "List the work queues available to the signed-in user."
  [documents-store forms-store users-store access-store]
  (fn [req]
    (let [user (auth/current-user users-store req)
          qs   (my-queues forms-store access-store user)]
      (page user "Queues" [{:label "Documents" :href "/"} {:label "Queues"}]
            [:h1 "Work queues"]
            [:p.muted "Documents on the forms you handle, grouped by workflow state — across all owners."]
            (if (seq qs)
              (into [:ul.sv-queues]
                    (for [{:keys [id title workflow]} qs]
                      (let [docs (docs-for-form documents-store id)
                            open (remove #(get-in workflow [:states (documents/workflow-state % (:initial workflow)) :terminal?])
                                         docs)]
                        [:li [:a {:href (str "/queue/" (name id))} (or title (name id))]
                         " " [:span.muted (str (count open) " open · " (count docs) " total")]])))
              [:p.muted "No queues yet — queues appear for workflowed forms you hold a role on."])))))

(defn for-form
  "One form's queue: its documents grouped by workflow state, in state order."
  [documents-store forms-store users-store access-store]
  (fn [req]
    (let [user    (auth/current-user users-store req)
          form-id (keyword (get-in req [:path-params :form]))
          form    (forms/get-form forms-store form-id)]
      (if-not (and form (workflowed? form) (access/team-member? access-store user form-id))
        (resp/redirect "/queue" :see-other)
        (let [wf       (:workflow form)
              title    (or (:title form) (name form-id))
              docs     (docs-for-form documents-store form-id)
              by-state (group-by #(documents/workflow-state % (:initial wf)) docs)]
          (apply page user (str "Queue — " title)
                 [{:label "Documents" :href "/"} {:label "Queues" :href "/queue"} {:label title}]
                 [:h1 (str title " queue")]
                 (for [state (keys (:states wf))
                       :let  [items (sort-by #(get-in % [:meta :modified-at]) > (get by-state state))]]
                   [:section
                    [:h2 [:span.sv-state (name state)] " " [:span.muted (str "(" (count items) ")")]]
                    (if (seq items)
                      (into [:table [:tr [:th "Owner"] [:th "Updated"] [:th]]]
                            (for [d items]
                              [:tr
                               [:td (or (:display-name (users/get-user users-store (:created-by d))) (:created-by d))]
                               [:td.muted (some-> (get-in d [:meta :modified-at]) (java.util.Date.) str)]
                               [:td [:a {:href (str "/doc/" (:id d))} "Open →"]]]))
                      [:p.muted "Nothing here."])])))))))
