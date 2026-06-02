(ns yogthos.stepvine.web.admin
  "Admin UI — assign roles to users and roles to forms. Gated to :admin users by
   `wrap-admin`. Plain Ring handlers (like the auth layer) so they render HTML and
   set nothing on the session."
  (:require
   [clojure.string :as str]
   [hiccup2.core :as h]
   [ring.util.response :as resp]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.security :as security]))

(defn- page [title body]
  (-> (resp/response
       (str "<!DOCTYPE html>"
            (h/html
             [:html {:lang "en"}
              [:head [:meta {:charset "utf-8"}]
               [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
               [:title (str "Admin — " title)]
               [:style "body{font-family:system-ui,sans-serif;max-width:48rem;margin:2.5rem auto;line-height:1.5}"
                       "table{border-collapse:collapse;width:100%} td,th{border:1px solid #e5e7eb;padding:.4rem .5rem;text-align:left}"
                       "nav a{margin-right:.8rem} input{padding:.25rem .4rem;border:1px solid #d1d5db;border-radius:.375rem}"
                       "button{padding:.25rem .6rem;border:1px solid #d1d5db;border-radius:.375rem;background:#fff;cursor:pointer}"
                       ".muted{color:#6b7280} code{background:#f3f4f6;padding:0 .25rem;border-radius:.25rem}"]]
              [:body
               [:nav [:a {:href "/admin/users"} "Users"] [:a {:href "/admin/forms"} "Forms"]
                [:a {:href "/"} "← Back to app"]]
               [:h1 title]
               body]])))
      (resp/content-type "text/html")))

(defn- roles-str [roles] (str/join " " (sort (map name roles))))

(defn parse-roles
  "Parse a space/comma-separated role string into a set of keywords."
  [s]
  (into #{} (comp (remove str/blank?) (map keyword))
        (str/split (or s "") #"[,\s]+")))

;; --- Users -> roles -------------------------------------------------------

(defn users-page [users-store]
  (fn [_req]
    (page "Users"
          [:div
           [:p.muted "Assign roles (space-separated). " [:code "admin"]
            " grants the admin UI and access to every form."]
           (into [:table [:tr [:th "User"] [:th "Roles"] [:th]]]
                 (for [u (sort-by :username (users/list-users users-store))]
                   [:tr
                    [:td (:display-name u) " " [:small.muted (:username u)]]
                    [:td
                     [:form {:method "post" :action (str "/admin/users/" (:id u) "/roles")}
                      (security/csrf-field)
                      [:input {:name "roles" :value (roles-str (users/roles u)) :size 24}]
                      " " [:button "Save"]]]
                    [:td.muted (when (users/admin? u) "admin")]]))])))

(defn set-user-roles [users-store]
  (fn [req]
    (users/set-roles! users-store (get-in req [:path-params :id])
                      (parse-roles (get-in req [:params :roles])))
    (resp/redirect "/admin/users" :see-other)))

;; --- Forms -> roles -------------------------------------------------------

(defn forms-page [forms-store access-store]
  (fn [_req]
    (page "Forms"
          [:div
           [:p.muted "Restrict a form to roles (space-separated). Empty = open to "
            "all signed-in users."]
           (into [:table [:tr [:th "Form"] [:th "Required roles"] [:th]]]
                 (for [id (sort (forms/list-forms forms-store))]
                   [:tr
                    [:td (or (:title (forms/get-form forms-store id)) (name id)) " "
                     [:small.muted (name id)]]
                    [:td
                     [:form {:method "post" :action (str "/admin/forms/" (name id) "/roles")}
                      (security/csrf-field)
                      [:input {:name "roles" :value (roles-str (access/form-roles access-store id)) :size 24}]
                      " " [:button "Save"]]]
                    [:td.muted (when (empty? (access/form-roles access-store id)) "open")]]))])))

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
