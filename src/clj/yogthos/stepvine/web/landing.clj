(ns yogthos.stepvine.web.landing
  "Landing + index-lookup views (pure hiccup), inside the shared chrome.

   - `landing-html`   — the signed-in user's home: the forms they can access (by
                        role), each with a create control and their documents.
   - `index-page-html`— the key-lookup page for an index form (§15.13): enter a
                        key (e.g. an MRN) to create a prepopulated document.

   These are the views for `cells.document`'s `:index/render` and `:doc/new-page`
   cells (and the no-match re-render in its `do-create`); the cell stays
   parse→orchestrate→view, matching `cells.form`."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.layout :as layout]
   [yogthos.stepvine.web.security :as security]))

(defn- create-control [{:keys [id title index?]}]
  (if index?
    ;; index forms start from a key lookup (§15.13)
    [:a.btn {:href (str "/form/" (name id) "/new")} (str "+ New " (or title (name id)) "…")]
    [:form {:method "post" :action (str "/form/" (name id) "/new")}
     (security/csrf-field)
     [:button (str "+ New " (or title (name id)))]]))

(defn- doc-row [users user {:keys [id form-id created-by shared status meta]}]
  (let [owner? (= (:id user) created-by)
        state  (get-in meta [:workflow :state])]
    [:div.doc
     [:a {:href (str "/doc/" id)} (str (name form-id))] " " [:small id]
     [:small.badge (str " · " (name (or status :in-progress)))]
     (when state [:small.badge (str " · " (name state))])
     (if owner? [:small " · owner"] [:small " · shared with you"])
     (when owner?
       [:span
        [:form {:method "post" :action (str "/doc/" id "/share")}
         (security/csrf-field)
         [:input {:name "username" :placeholder "share with username"}]
         [:button "Share"]]
        (when (seq shared)
          [:small " — shared with "
           (str/join ", " (keep #(:username (users/get-user users %)) shared))])
        [:form {:method "post" :action (str "/doc/" id "/delete")}
         (security/csrf-field)
         [:button "Delete"]]])]))

(def ^:private landing-styles
  (str ".doc{padding:.5rem 0;border-bottom:1px solid #eee} small{color:#6b7280} form{display:inline}"
       ".bar{display:flex;justify-content:space-between;align-items:center}"
       ".form-sec{margin:1.5rem 0;padding-top:.5rem;border-top:2px solid #eef0f3}"
       ".sv-content input{padding:.2rem .4rem;border:1px solid #d1d5db;border-radius:.375rem}"
       ".sv-content button,.sv-content a.btn{padding:.25rem .6rem;border:1px solid #d1d5db;"
       "border-radius:.375rem;background:#fff;cursor:pointer;text-decoration:none;color:#111;margin-left:.3rem}"
       ".badge{color:#6b7280} .filter{margin-bottom:1rem} .filter a{margin-left:0;margin-right:.3rem}"))

(defn landing-html
  "The signed-in user's home: the forms they can access (by role), each with a
   create control and their documents of that form — inside the shared chrome."
  [{:keys [forms docs-by-form user users status]}]
  (layout/page
   {:user user :title "Documents" :crumbs [{:label "Documents"}]
    :head [:style landing-styles]}
   [:p.filter "Show: "
    (for [[k label] [[nil "all"] [:in-progress "in progress"] [:submitted "submitted"]
                     [:completed "completed"]]]
      [:a.btn {:href (str "/" (when k (str "?status=" (name k))))} label])]
   (if (seq forms)
     (into [:div]
           (for [{:keys [id] :as f} forms]
             (let [docs (cond->> (get docs-by-form id)
                          status (filter #(= status (:status %))))]
               [:div.form-sec
                [:div.bar [:h2 (or (:title f) (name id))] (create-control f)]
                (if (seq docs)
                  (into [:div] (map #(doc-row users user %) docs))
                  [:p.badge "No documents yet."])])))
     [:p "You don't have access to any forms yet. Ask an admin for a role."])))

(defn index-page-html
  "The lookup page for an index form (§15.13): enter the key (e.g. an MRN), submit
   to create a prepopulated document — inside the shared chrome."
  [user form-id form value error]
  (let [{:keys [prompt]} (:index form)
        title (str "New " (or (:title form) (name form-id)))]
    (layout/page
     {:user user :title title
      :crumbs [{:label "Documents" :href "/"} {:label title}]
      :head [:style ".sv-content input{padding:.35rem .5rem;border:1px solid #d1d5db;border-radius:.375rem}"
                    ".sv-content button{padding:.35rem .8rem;border:1px solid #d1d5db;border-radius:.375rem;background:#2563eb;color:#fff;cursor:pointer}"
                    ".error{color:#b91c1c} label{display:block;margin:.75rem 0}"]}
     [:h1 title]
     [:form {:method "post" :action (str "/form/" (name form-id) "/new")}
      (security/csrf-field)
      [:label (or prompt "Lookup key")
       [:br] [:input {:name "index-key" :value (or value "") :autofocus "autofocus"}]]
      (when error [:p.error error])
      [:button "Look up & create"]]
     [:p [:a {:href "/"} "← Cancel"]])))
