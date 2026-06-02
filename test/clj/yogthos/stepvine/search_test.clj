(ns yogthos.stepvine.search-test
  "Server-side typeahead (parity stepvine-m8h): the source is filtered on the
   server and the widget ships no option list to the browser."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.sources :as sources]
   [yogthos.stepvine.components.widgets.selection.search-select]))  ; register the widget

(def ^:private countries
  [["Argentina" "AR"] ["Canada" "CA"] ["China" "CN"]
   ["United Kingdom" "GB"] ["United States" "US"]])

(deftest source-filters-on-the-server
  (let [src (sources/resolve-source {} {:kind :static :data countries})]
    (testing "a query matches the label (case-insensitive) and returns only matches"
      (is (= [["Canada" "CA"]] (src "can")))
      (is (= [["China" "CN"]] (src "CHI")))
      (is (= [["United Kingdom" "GB"] ["United States" "US"]] (src "united"))))
    (testing "the full list is available with no query (the handler guards blank)"
      (is (= countries (src ""))))))

(deftest search-select-renders-without-the-options
  (let [html (render/render-view
              {:doc-id "d1" :values {} :aliases {"c" "stepvine.components"}}
              [:c/search-select {:id :country :label "Country" :source :countries}])]
    (testing "a search box that posts to the server search endpoint"
      (is (str/includes? html "data-on:input"))
      (is (str/includes? html "/doc/d1/search/country?source=countries"))
      (is (str/includes? html "id=\"search-country\"")))
    (testing "the option list is NOT in the markup (server-side only)"
      (is (not (str/includes? html "Canada")))
      (is (not (str/includes? html "United"))))
    (testing "it declares its ad-hoc query/open signals + binds by NAME (no $)"
      (is (str/includes? html "country_q"))
      (is (str/includes? html "country_open"))
      (is (str/includes? html "data-bind=\"country_q\"")))))
