(ns yogthos.stepvine.web.admin
  "Admin UI — assign roles to users and roles to forms. Gated to :admin users by
   `wrap-admin`. Plain Ring handlers (like the auth layer) so they render HTML and
   set nothing on the session."
  (:require
   [clojure.string :as str]
   [ring.util.response :as resp]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.layout :as layout]
   [yogthos.stepvine.web.security :as security]))

(def ^:private admin-styles
  (str "table{border-collapse:collapse;width:100%} td,th{border:1px solid #e5e7eb;padding:.4rem .5rem;text-align:left}"
       ".subnav a{margin-right:.8rem} .sv-content input{padding:.25rem .4rem;border:1px solid #d1d5db;border-radius:.375rem}"
       ".sv-content button{padding:.25rem .6rem;border:1px solid #d1d5db;border-radius:.375rem;background:#fff;cursor:pointer}"
       ".muted{color:#6b7280} code{background:#f3f4f6;padding:0 .25rem;border-radius:.25rem} form{display:inline}"))

(defn- page [user title body]
  (-> (resp/response
       (layout/page
        {:user user :title (str "Admin — " title)
         :crumbs [{:label "Documents" :href "/"} {:label "Admin" :href "/admin/users"} {:label title}]
         :head [:style admin-styles]}
        [:p.subnav [:a {:href "/admin/users"} "Users"] [:a {:href "/admin/forms"} "Forms"]]
        [:h1 title]
        body))
      (resp/content-type "text/html")))

(defn- roles-str [roles] (str/join " " (sort (map name roles))))

(defn parse-roles
  "Parse a space/comma-separated role string into a set of keywords."
  [s]
  (into #{} (comp (remove str/blank?) (map keyword))
        (str/split (or s "") #"[,\s]+")))

;; --- Users -> roles -------------------------------------------------------

(defn users-page [users-store]
  (fn [req]
    (let [me (auth/current-user req users-store)]
      (page me "Users"
            [:div
             [:p.muted "Assign roles (space-separated). " [:code "admin"]
              " grants the admin UI and access to every form."]
             (into [:table [:tr [:th "User"] [:th "Roles"] [:th "Reset password"] [:th]]]
                   (for [u (sort-by :username (users/list-users users-store))]
                     [:tr
                      [:td (:display-name u) " " [:small.muted (:username u)]
                       (when (:oauth u) [:small.muted " · SSO"])]
                      [:td
                       [:form {:method "post" :action (str "/admin/users/" (:id u) "/roles")}
                        (security/csrf-field)
                        [:input {:name "roles" :value (roles-str (users/roles u)) :size 18}]
                        " " [:button "Save"]]]
                      [:td
                       (when-not (:oauth u)
                         [:form {:method "post" :action (str "/admin/users/" (:id u) "/password")}
                          (security/csrf-field)
                          [:input {:name "password" :type "password" :placeholder "new password" :size 14}]
                          " " [:button "Set"]])]
                      [:td
                       (when (not= (:id u) (:id me))         ; can't delete yourself
                         [:form {:method "post" :action (str "/admin/users/" (:id u) "/delete")}
                          (security/csrf-field)
                          [:button "Delete"]])]]))
             [:h2 "Add user"]
             [:form {:method "post" :action "/admin/users/new"}
              (security/csrf-field)
              [:input {:name "username" :placeholder "username" :required true}] " "
              [:input {:name "display-name" :placeholder "display name"}] " "
              [:input {:name "password" :type "password" :placeholder "password" :required true}] " "
              [:input {:name "roles" :placeholder "roles" :size 14}] " "
              [:button "Create user"]]]))))

(defn set-user-roles [users-store]
  (fn [req]
    (users/set-roles! users-store (get-in req [:path-params :id])
                      (parse-roles (get-in req [:params :roles])))
    (resp/redirect "/admin/users" :see-other)))

(defn create-user [users-store]
  (fn [req]
    (let [{:keys [username password display-name roles]} (:params req)]
      (when (and (seq username) (seq password)
                 (not (users/find-by-username users-store username)))
        (users/create! users-store {:username username :password password
                                    :display-name display-name :roles (parse-roles roles)}))
      (resp/redirect "/admin/users" :see-other))))

(defn set-user-password [users-store]
  (fn [req]
    (when (seq (get-in req [:params :password]))
      (users/set-password! users-store (get-in req [:path-params :id]) (get-in req [:params :password])))
    (resp/redirect "/admin/users" :see-other)))

(defn delete-user [users-store]
  (fn [req]
    ;; never delete the acting admin
    (when (not= (get-in req [:path-params :id]) (:id (auth/current-user req users-store)))
      (users/delete! users-store (get-in req [:path-params :id])))
    (resp/redirect "/admin/users" :see-other)))

;; --- Forms -> roles -------------------------------------------------------

(defn forms-page [forms-store access-store users-store]
  (fn [req]
    (page (auth/current-user req users-store) "Forms"
          [:div
           [:p.muted "Edit an app's EDN + CSS live, or restrict it to roles "
            "(space-separated; empty = open to all signed-in users)."]
           (into [:table [:tr [:th "App"] [:th "Required roles"] [:th] [:th]]]
                 (for [id (sort-by name (forms/list-forms forms-store))]
                   [:tr
                    [:td (or (:title (forms/get-form forms-store id)) (name id)) " "
                     [:small.muted (name id) (when (forms/css forms-store id) " · styled")]]
                    [:td
                     [:form {:method "post" :action (str "/admin/forms/" (name id) "/roles")}
                      (security/csrf-field)
                      [:input {:name "roles" :value (roles-str (access/form-roles access-store id)) :size 18}]
                      " " [:button "Save"]]]
                    [:td.muted (when (empty? (access/form-roles access-store id)) "open")]
                    [:td [:a {:href (str "/admin/forms/" (name id) "/edit")} "Edit"]]]))
           [:h2 "New app"]
           [:form {:method "post" :action "/admin/forms/new"}
            (security/csrf-field)
            [:input {:name "id" :placeholder "app-id (keyword)" :required true}] " "
            [:input {:name "title" :placeholder "title"}] " "
            [:button "Create & edit"]]])))

(defn set-form-roles [access-store]
  (fn [req]
    (access/set-form-roles! access-store (get-in req [:path-params :id])
                            (parse-roles (get-in req [:params :roles])))
    (resp/redirect "/admin/forms" :see-other)))

;; --- Middleware -----------------------------------------------------------

(defn wrap-admin
  "Allow only :admin users through; everyone else is sent back to the app."
  [users-store handler]
  (fn [req]
    (let [user (users/get-user users-store (auth/current-user-id req))]
      (if (users/admin? user)
        (handler req)
        (resp/redirect "/" :see-other)))))
