(ns yogthos.stepvine.web.report
  "In-browser HTML report view (parity stepvine-q69): GET /doc/:id/reports/:rid
   renders a form's declared `:report` to HTML (value substitution + transforms +
   markdown), inside the page chrome, with a Print button. Complements the
   downloadable clj-pdf report."
  (:require
   [hiccup2.core :as h]
   [ring.util.response :as resp]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.reports :as reports]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.web.layout :as layout]))

(def ^:private report-css
  (str ".sv-report{max-width:46rem;margin:0 auto}"
       ".sv-report table{border-collapse:collapse;width:100%;margin:.75rem 0}"
       ".sv-report td,.sv-report th{border:1px solid #e5e7eb;padding:.4rem .6rem;text-align:left}"
       ".sv-report h1,.sv-report h2,.sv-report h3{margin:1rem 0 .4rem}"
       ".sv-report-print{float:right;padding:.3rem .8rem;border:1px solid #d1d5db;border-radius:.375rem;background:#fff;cursor:pointer}"
       "@media print{.sv-topbar,.sv-footer,.sv-report-print{display:none}}"))

(defn handler
  "Build the report ring handler closed over the document resources."
  [{:keys [session-manager users] :as resources}]
  (fn [req]
    (let [doc-id (get-in req [:path-params :id])
          rid    (get-in req [:path-params :rid])]
      (docs/ensure! resources doc-id)
      (if-let [{:keys [title html]} (reports/report (session/current session-manager doc-id) rid)]
        (-> (resp/response
             (layout/page
              {:user   (auth/current-user users req)
               :title  (or title "Report")
               :crumbs [{:label "Documents" :href "/"} {:label "Report"}]
               :head   [:style report-css]}
              [:div.sv-report
               [:button.sv-report-print {:onclick "window.print()"} "Print"]
               (h/raw html)]))
            (resp/content-type "text/html"))
        {:status 404 :body "No such report."}))))
