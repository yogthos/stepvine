(ns yogthos.stepvine.render-test
  "Phase 2: the server-side renderer emits HTML carrying Datastar bindings."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :as render]
   yogthos.stepvine.components   ; register widget render methods
   [yogthos.stepvine.editor.impl :as impl]))

(deftest signal-name-sanitizes-ids
  (is (= "kg"           (signals/signal-name :kg)))
  (is (= "overweight"   (signals/signal-name :overweight?)))
  (is (= "bmi_category" (signals/signal-name :bmi-category))))

(deftest theme-href-resolves-view-opts
  (let [sess (fn [theme]
               {:form {:views {:default {:opts (when theme {:theme theme})}}}})]
    (testing "no theme opt → nil (default stylesheet only)"
      (is (nil? (render/theme-href (sess nil) :default))))
    (testing "bare name → /css/<name>.css"
      (is (= "/css/dark.css" (render/theme-href (sess "dark") :default)))
      (is (= "/css/dark.css" (render/theme-href (sess :dark) :default))))
    (testing "absolute path passes through verbatim"
      (is (= "/assets/brand.css" (render/theme-href (sess "/assets/brand.css") :default))))
    (testing "full URL passes through verbatim"
      (is (= "https://cdn.example.com/t.css"
             (render/theme-href (sess "https://cdn.example.com/t.css") :default))))))

(deftest resolve-component-expands-aliases
  (let [aliases {"c" "stepvine.components"}]
    (is (= :stepvine.components/input-field
           (render/resolve-component aliases :c/input-field)))
    ;; namespaced-but-unaliased passes through unchanged
    (is (= :other.ns/thing
           (render/resolve-component aliases :other.ns/thing)))))

(defn- render-bmi [changes]
  (let [form    (forms/load-form "bmi")
        session (impl/apply-changes (impl/create-session form {}) changes)
        ctx     (render/session->context session :default "bmi")]
    (render/render-view ctx (render/view-markup session :default))))

(deftest renders-datastar-bound-html
  (let [html (render-bmi [[:kg 100] [:m 2]])]
    (testing "form seeds all signals and suppresses native submit"
      (is (str/includes? html "data-init="))   ; opens the SSE stream on load
      ;; data-signals JSON (HTML-escaped quotes) holds field + reaction values
      (is (str/includes? html "data-signals="))
      (is (str/includes? html "kg&quot;:100"))
      (is (str/includes? html "overweight&quot;:true"))
      (is (str/includes? html "bmi_category&quot;:&quot;overweight")))

    (testing "editable input: two-way bind + debounced POST of intent"
      (is (str/includes? html "data-bind=\"kg\""))
      (is (str/includes? html "data-on:input__debounce.300ms"))
      (is (str/includes? html "/doc/bmi/field/kg"))
      (is (str/includes? html "value=\"100\"")))

    (testing "computed field rendered read-only and signal-bound"
      (is (str/includes? html "data-bind=\"bmi\""))
      (is (str/includes? html "readonly"))
      (is (str/includes? html "value=\"25\"")))

    (testing "reaction text binding + conditional block"
      (is (str/includes? html "data-text=\"$bmi_category\""))
      (is (str/includes? html ">overweight</span>"))
      (is (str/includes? html "data-show=\"$overweight\"")))

    (testing "plain HTML and text content pass through"
      (is (str/includes? html "<h1>BMI Calculator</h1>"))
      (is (str/includes? html "Category: ")))))

(deftest empty-document-renders-blank-values
  (let [html (render-bmi [])]
    (is (str/includes? html "value=\"\""))
    ;; nil bmi -> category "n/a"
    (is (str/includes? html ">n/a</span>"))))
