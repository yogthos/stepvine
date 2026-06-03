(ns yogthos.stepvine.web.doc-search
  "Document content search (§j00): GET /search?q=… lists the documents the
   signed-in user has access to whose content matches the query. Auth is enforced
   structurally — the results come from documents/search-accessible, which only
   ever considers the user's own/shared documents."
  (:require
   [clojure.string :as str]
   [ring.util.response :as resp]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms-compile :as forms-compile]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.users :as users]
   [yogthos.stepvine.web.layout :as layout])
  (:import
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(def ^:private date-fmt (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd") (ZoneId/systemDefault)))

(defn- fmt-date [ms]
  (when ms (.format date-fmt (Instant/ofEpochMilli ms))))

(defn- result-item [forms d]
  (let [f (forms-compile/get-form forms (:form-id d))]
    [:li.sv-result
     [:a {:href (str "/doc/" (:id d))}
      [:span.sv-result-title (or (:title f) (name (:form-id d)))]
      [:span.sv-result-meta
       (str/join " · " (remove str/blank?
                               [(some-> (:status d) name)
                                (fmt-date (get-in d [:meta :modified-at] (:created-at d)))]))]]]))

(defn- search-view [q results forms]
  (list
   [:h1 "Search documents"]
   [:form.sv-search-page {:method "get" :action "/search" :role "search"}
    [:input.sv-search-input
     {:type "search" :name "q" :value q :autofocus true
      :placeholder "Search documents you have access to…"}]
    [:button.sv-search-go {:type "submit"} "Search"]]
   (cond
     (str/blank? q)
     [:p.sv-muted "Enter a term to search the content of documents you can access."]
     (empty? results)
     [:p.sv-muted (str "No accessible documents match “" q "”.")]
     :else
     (list
      [:p.sv-muted (str (count results) " match" (when (not= 1 (count results)) "es"))]
      (into [:ul.sv-search-results] (map (partial result-item forms) results))))))

(defn handler
  "Build the document-search handler closed over the page resources."
  [{:keys [documents forms users]}]
  (fn [req]
    (let [uid     (get-in req [:session :user-id])
          user    (users/get-user users uid)
          q       (str/trim (or (get-in req [:query-params "q"]) ""))
          results (when (seq q) (documents/search-accessible documents uid q))]
      (-> (resp/response
           (layout/page
            {:user user :title "Search"
             :crumbs [{:label "Documents" :href "/"} {:label "Search"}]}
            (search-view q results forms)))
          (resp/content-type "text/html")))))
