(ns yogthos.stepvine.web.layout
  "Shared page chrome — a navbar (brand + breadcrumbs + signed-in user + sign-out)
   and a footer, wrapping every server-rendered page so navigation is consistent.
   The styling lives in the external theme (`/css/stepvine.css`), so the document
   editor and the plain HTML pages share one look."
  (:require
   [hiccup2.core :as h]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.security :as security]))

(defn breadcrumbs
  "An ordered breadcrumb trail. `crumbs` is `[{:label :href} …]`; the last is the
   current page (never a link)."
  [crumbs]
  (when (seq crumbs)
    (into [:ol.sv-crumbs]
          (map-indexed
           (fn [i {:keys [label href]}]
             [:li (if (and href (not= i (dec (count crumbs))))
                    [:a {:href href} label]
                    [:span label])])
           crumbs))))

(defn navbar
  "Top navigation bar: brand + breadcrumbs on the left, the signed-in user + an
   Admin link (admins) + sign-out on the right."
  [user crumbs]
  [:header.sv-topbar
   [:div.sv-topbar-left
    [:a.sv-brand {:href "/"} "▦ Stepvine"]
    (breadcrumbs crumbs)]
   [:div.sv-topbar-right
    (when user
      [:span.sv-user (:display-name user)
       (when (users/admin? user) [:span.sv-role "admin"])])
    (when (users/admin? user) [:a.sv-navlink {:href "/admin/users"} "Admin"])
    (when user
      [:form {:method "post" :action "/logout"}
       (security/csrf-field)
       [:button.sv-logout "Sign out"]])]])

(defn footer []
  [:footer.sv-footer
   [:span "Stepvine — server-authoritative reactive forms"]
   [:span.sv-foot-links [:a {:href "/"} "Documents"]]])

(defn page
  "A full HTML page with the shared chrome. `opts`: :user :title :crumbs :head
   (extra <head> hiccup). `body` is the page content (wrapped in centered .sv-content)."
  [{:keys [user title crumbs head]} & body]
  (str "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title (str (when title (str title " — ")) "Stepvine")]
          [:link {:rel "stylesheet" :href "/css/stepvine.css"}]
          head]
         [:body
          (navbar user crumbs)
          (into [:main.sv-content] body)
          (footer)]])))

(defn navbar-html "Navbar rendered to an HTML string (for the Selmer editor shell)." [user crumbs]
  (str (h/html (navbar user crumbs))))
(defn footer-html [] (str (h/html (footer))))
