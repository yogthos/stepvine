(ns yogthos.stepvine.app-css-test
  "App-owned styling: a form's sibling <id>.css is loaded into its :css and served
   live per-app (re-skin without redeploy); CSS is not version-pinned."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [integrant.core :as ig]
   [yogthos.stepvine.forms-compile :as forms-compile]
   [yogthos.stepvine.forms :as forms]))

(deftest sibling-css-loads-into-the-form
  (testing "an app with a sibling .css carries it in :css"
    (let [t (forms/load-form "forms" "ticket")]
      (is (string? (:css t)))
      (is (str/includes? (:css t) ".sv-doc-body"))))
  (testing "an app without a .css has no :css"
    (is (nil? (:css (forms/load-form "forms" "bmi"))))))

(deftest app-css-href-and-store
  (let [store (ig/init-key :store/forms {:dir "forms" :versions-file nil})]
    (testing "the store exposes the app's live CSS + a cache-busting href"
      (is (str/includes? (forms/css store :ticket) ".wf-btn"))
      (is (re-matches #"/app/ticket/style\.css\?v=[0-9a-f]{12}" (forms/app-css-href store :ticket))))
    (testing "an app with no CSS has no href"
      (is (nil? (forms/app-css-href store :bmi))))
    (testing "CSS is excluded from the versioned archive (live, not pinned)"
      (is (nil? (:css (forms-compile/get-form-version store :ticket 1)))))))
