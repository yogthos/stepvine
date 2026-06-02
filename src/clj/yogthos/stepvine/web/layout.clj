(ns yogthos.stepvine.web.layout
  "Shared page chrome — a navbar (brand + breadcrumbs + signed-in user + sign-out)
   and a footer, wrapping every server-rendered page so navigation is consistent.
   The styling lives in the external theme (`/css/stepvine.css`), so the document
   editor and the plain HTML pages share one look."
  (:require
   [clojure.string :as str]
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

(defn- avatar [user]
  (let [nm (or (not-empty (:display-name user)) (:username user) "?")]
    [:span.sv-avatar {:title nm} (str/upper-case (subs nm 0 1))]))

(defn navbar
  "Top navigation bar: brand + breadcrumbs on the left; on the right the admin
   links (Apps editor + Users), the signed-in user (avatar + name + role) and a
   sign-out button — or a Sign in link when logged out."
  [user crumbs]
  [:header.sv-topbar
   [:div.sv-topbar-left
    [:a.sv-brand {:href "/"} "▦ Stepvine"]
    (breadcrumbs crumbs)]
   [:div.sv-topbar-right
    (if user
      [:span.sv-usermenu
       (when (users/admin? user)
         (list [:a.sv-navlink {:href "/admin/forms" :title "App editor"} "Apps"]
               [:a.sv-navlink {:href "/admin/users"} "Admin"]))
       (avatar user)
       [:span.sv-user (:display-name user)
        (when (users/admin? user) [:span.sv-role "admin"])]
       [:form.sv-logout-form {:method "post" :action "/logout"}
        (security/csrf-field)
        [:button.sv-logout {:title "Sign out"} "Sign out"]]]
      [:a.sv-navlink.sv-signin {:href "/login"} "Sign in"])]])

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

;; --- multi-page forms -----------------------------------------------------

(defn- current-page-index [pages current-vid]
  (or (first (keep-indexed
              (fn [i p] (when (= (keyword (:view p)) (keyword current-vid)) i))
              pages))
      0))

(defn page-tabs
  "Breadcrumb-style page navigation for a multi-page form — one clickable tab per
   page (a form view), the current one highlighted. Each page is a different view
   over the same shared document data. Returns nil for single-page forms."
  [pages current-vid doc-id]
  (when (> (count pages) 1)
    (let [idx (current-page-index pages current-vid)]
      (into [:nav.sv-pages {:aria-label "Pages"}]
            (map-indexed
             (fn [i {:keys [view label]}]
               [:a.sv-page {:href  (str "/doc/" doc-id "?view=" (name view))
                            :class (when (= i idx) "active")
                            :aria-current (when (= i idx) "page")}
                [:span.sv-page-num (str (inc i))]
                [:span.sv-page-label (or label (name view))]])
             pages)))))

(defn page-prevnext
  "Prev / position / next controls for the bottom of a multi-page form. Returns
   nil for single-page forms."
  [pages current-vid doc-id]
  (when (> (count pages) 1)
    (let [idx  (current-page-index pages current-vid)
          href (fn [{:keys [view]}] (str "/doc/" doc-id "?view=" (name view)))
          prev (get pages (dec idx))
          nxt  (get pages (inc idx))]
      [:div.sv-pagenav
       (if prev
         [:a.sv-pagenav-prev {:href (href prev)} "← " (:label prev)]
         [:span.sv-pagenav-spacer])
       [:span.sv-pagenav-pos (str "Page " (inc idx) " of " (count pages))]
       (if nxt
         [:a.sv-pagenav-next {:href (href nxt)} (:label nxt) " →"]
         [:span.sv-pagenav-spacer])])))

(defn page-tabs-html [pages current-vid doc-id]
  (if-let [t (page-tabs pages current-vid doc-id)] (str (h/html t)) ""))
(defn page-prevnext-html [pages current-vid doc-id]
  (if-let [n (page-prevnext pages current-vid doc-id)] (str (h/html n)) ""))
