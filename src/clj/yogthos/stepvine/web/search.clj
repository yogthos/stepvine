(ns yogthos.stepvine.web.search
  "Server-side typeahead search (parity stepvine-m8h): POST /doc/:id/search/:fid
   reads the typed query, queries the field's form source ON THE SERVER, and morphs
   only the MATCHING options into the widget's results list — the full list never
   reaches the browser. A one-shot datastar response, to the searching client only."
  (:require
   [clojure.string :as str]
   [hiccup2.core :as h]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.ring :as ds-ring]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.sources :as sources]
   [yogthos.stepvine.web.request :as request]))


(defn- results-markup
  "The results <ul> (the morph target). Each match is a button that, on click,
   sets the field signal + display text and persists via the field endpoint."
  [doc-id fid sig matches]
  (let [field-url (str "/doc/" doc-id "/field/" (name fid))]
    [:ul {:id (str "search-" sig) :class "ss-results" "data-show" (str "$" sig "_open")}
     (if (seq matches)
       (for [o matches]
         (let [v (str (sources/option-value o)) l (str (sources/option-label o))]
           [:li [:button {:type "button"
                          "data-on:click"
                          (str "$" sig "=" (pr-str v) "; $" sig "_q=" (pr-str l)
                               "; $" sig "_open=false; @post('" field-url "')")}
                 l]]))
       [:li.ss-empty "No matches"])]))

(defn handler
  "Build the search ring handler closed over the document resources (forms,
   documents, session-manager, options-store)."
  [resources]
  (fn [req]
    (let [doc-id  (get-in req [:path-params :id])
          fid     (keyword (get-in req [:path-params :fid]))
          sig     (signals/signal-name fid)
          src-id  (some-> (get-in req [:query-params "source"]) not-empty keyword)
          signals (request/read-signals req)
          query   (get signals (str sig "_q"))
          spec    (when src-id (get-in (:form-raw (docs/ensure! resources doc-id)) [:sources src-id]))
          matches (when (and spec (not (str/blank? (str query))))
                    (take 25 ((sources/resolve-source
                               {:options-store (:options-store resources)} spec)
                              query)))
          html    (str (h/html (results-markup doc-id fid sig matches)))]
      (ds-ring/->sse-response
       req {ds-ring/on-open (fn [gen] (d*/patch-elements! gen html))}))))
