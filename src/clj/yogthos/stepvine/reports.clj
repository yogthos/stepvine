(ns yogthos.stepvine.reports
  "In-browser HTML/Markdown reports (parity stepvine-q69).

   A form declares `:reports {<id> {:title .. :body <hiccup>}}`. The body is
   ordinary hiccup with two special nodes substituted from the document's current
   values:

     [:val <field-or-reaction-id> {opts}]  -> the value, transformed
     [:markdown \"…\"]                       -> rendered markdown

   Value transforms (in `opts`): `:join` (a collection -> joined string),
   `:fmt` (printf-style, e.g. \"$%.2f\"), `:upper`, `:lower`. Unlike the live form
   this is a STATIC snapshot rendered to HTML — viewable in the browser and a
   complement to the clj-pdf export."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [hiccup2.core :as h]
   [markdown.core :as md]
   [yogthos.stepvine.exports :as exports]
   [yogthos.stepvine.format :as fmt]))

(defn- transform [v {:keys [join fmt upper lower]}]
  (let [v (cond->> v join (str/join (if (string? join) join ", ")))
        v (if fmt (fmt/fmt-value fmt v) (str v))
        v (cond-> v upper str/upper-case lower str/lower-case)]
    v))

(defn render-body
  "Replace `[:val id opts]` and `[:markdown s]` nodes in `body` with the resolved
   value (transformed) / rendered markdown, against the `values` map."
  [values body]
  (walk/postwalk
   (fn [node]
     (cond
       (and (vector? node) (= :val (first node)))
       (transform (get values (second node)) (or (get node 2) {}))
       (and (vector? node) (= :markdown (first node)))
       (h/raw (md/md-to-html-string (str (second node))))
       :else node))
   body))

(defn report
  "`{:title :html}` for the report `report-id` of a live session, or nil when the
   form declares no such report."
  [session report-id]
  (when-let [r (get-in session [:form :reports (keyword report-id)])]
    {:title (:title r)
     :html  (str (h/html (render-body (exports/export-values session) (:body r))))}))
