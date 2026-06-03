(ns yogthos.stepvine.value-format-test
  "Parity (stepvine-nod): printf-style :fmt for value/labeled-value display —
   server-side (initial render) and the live datastar data-text expression."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.format :as fmt]))

(deftest fmt-value-server-side
  (testing "decimals, prefix/suffix, integers"
    (is (= "58.32"   (fmt/fmt-value "%.2f" 58.32)))
    (is (= "$58.32"  (fmt/fmt-value "$%.2f" 58.32)))
    (is (= "58.3 kg" (fmt/fmt-value "%.1f kg" 58.32)))
    (is (= "58"      (fmt/fmt-value "%d" 58.4)))         ; rounds
    (is (= "59"      (fmt/fmt-value "%d" 58.6))))
  (testing "coerces numeric strings (field values arrive as strings)"
    (is (= "$60.00"  (fmt/fmt-value "$%.2f" "60"))))
  (testing "nil / empty / no-fmt / non-numeric pass through gracefully"
    (is (= ""    (fmt/fmt-value "%.2f" nil)))
    (is (= ""    (fmt/fmt-value "%.2f" "")))
    (is (= "hi"  (fmt/fmt-value nil "hi")))
    (is (= "n/a" (fmt/fmt-value "%.2f" "n/a")))))

(deftest fmt-text-expr-live
  (testing "no fmt -> the bare signal"
    (is (= "$total" (fmt/fmt-text-expr nil "$total"))))
  (testing "%.2f -> a toFixed(2) expression guarded against empty"
    (let [e (fmt/fmt-text-expr "%.2f" "$total")]
      (is (str/includes? e "Number($total).toFixed(2)"))
      (is (str/includes? e "==null"))))
  (testing "prefix/suffix are interpolated around the number"
    (let [e (fmt/fmt-text-expr "$%.2f" "$total")]
      (is (str/includes? e "\"$\""))
      (is (str/includes? e "toFixed(2)")))
    (is (str/includes? (fmt/fmt-text-expr "%.1f kg" "$w") "\" kg\""))))

(deftest fmt-spec-parsing
  (is (nil? (fmt/fmt-spec "no conversion here")))
  (is (= {:whole "%.2f" :pre "$" :digits "2" :type "f" :post ""}
         (fmt/fmt-spec "$%.2f")))
  (is (= {:whole "%d" :pre "" :digits "" :type "d" :post " items"}
         (fmt/fmt-spec "%d items"))))
