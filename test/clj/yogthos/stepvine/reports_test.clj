(ns yogthos.stepvine.reports-test
  "stepvine-q69: in-browser HTML/Markdown report view with value substitution
   and transforms."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.reports :as reports]))

(deftest render-body-substitutes-and-transforms
  (testing "[:val id] pulls a value; :fmt formats it printf-style"
    (let [values {:total 58.32 :who "alice"}
          out    (reports/render-body
                  values
                  [:div
                   [:span [:val :total {:fmt "$%.2f"}]]
                   [:b [:val :who {:upper true}]]])]
      (is (= [:div [:span "$58.32"] [:b "ALICE"]] out))))

  (testing ":join renders a collection; default separator is \", \""
    (is (= [:p "a, b, c"]
           (reports/render-body {:tags ["a" "b" "c"]} [:p [:val :tags {:join true}]])))
    (is (= [:p "a | b"]
           (reports/render-body {:tags ["a" "b"]} [:p [:val :tags {:join " | "}]]))))

  (testing ":lower transform"
    (is (= [:p "yes"] (reports/render-body {:x "YES"} [:p [:val :x {:lower true}]]))))

  (testing "missing value renders empty, not the literal node"
    (is (= [:p ""] (reports/render-body {} [:p [:val :nope {}]]))))

  (testing "[:markdown s] becomes raw HTML, left untouched by hiccup escaping"
    (let [out (reports/render-body {} [:div [:markdown "## Hi\n\n**bold**"]])
          html (str (hiccup2.core/html out))]
      (is (str/includes? html "<h2>Hi</h2>"))
      (is (str/includes? html "<strong>bold</strong>")))))

(deftest report-renders-live-order-totals
  (let [form (forms/load-form "order")
        sess (impl/apply-changes
              (impl/create-session form {})
              [[:qty 3] [:price 20] [:discount-pct 10] [:tax-rate 8]])
        {:keys [title html]} (reports/report sess "summary")]
    (testing "the declared report resolves by id"
      (is (= "Order Summary" title)))
    (testing "markdown heading is rendered"
      (is (str/includes? html "<h2>Order Summary</h2>"))
      (is (str/includes? html "<strong>order</strong>")))
    (testing "calculated fields cascade and render currency-formatted"
      ;; subtotal 60, discount 6, taxable 54, tax 4.32, total 58.32
      (is (str/includes? html "$60.00"))
      (is (str/includes? html "$6.00"))
      (is (str/includes? html "$4.32"))
      (is (str/includes? html "$58.32")))
    (testing "unknown report id yields nil"
      (is (nil? (reports/report sess "nope"))))))
