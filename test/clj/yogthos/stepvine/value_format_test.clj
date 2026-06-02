(ns yogthos.stepvine.value-format-test
  "Parity (stepvine-nod): printf-style :fmt for value/labeled-value display —
   server-side (initial render) and the live datastar data-text expression."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.render :as render]))

(deftest fmt-value-server-side
  (testing "decimals, prefix/suffix, integers"
    (is (= "58.32"   (render/fmt-value "%.2f" 58.32)))
    (is (= "$58.32"  (render/fmt-value "$%.2f" 58.32)))
    (is (= "58.3 kg" (render/fmt-value "%.1f kg" 58.32)))
    (is (= "58"      (render/fmt-value "%d" 58.4)))         ; rounds
    (is (= "59"      (render/fmt-value "%d" 58.6))))
  (testing "coerces numeric strings (field values arrive as strings)"
    (is (= "$60.00"  (render/fmt-value "$%.2f" "60"))))
  (testing "nil / empty / no-fmt / non-numeric pass through gracefully"
    (is (= ""    (render/fmt-value "%.2f" nil)))
    (is (= ""    (render/fmt-value "%.2f" "")))
    (is (= "hi"  (render/fmt-value nil "hi")))
    (is (= "n/a" (render/fmt-value "%.2f" "n/a")))))

(deftest fmt-text-expr-live
  (testing "no fmt -> the bare signal"
    (is (= "$total" (render/fmt-text-expr nil "$total"))))
  (testing "%.2f -> a toFixed(2) expression guarded against empty"
    (let [e (render/fmt-text-expr "%.2f" "$total")]
      (is (str/includes? e "Number($total).toFixed(2)"))
      (is (str/includes? e "==null"))))
  (testing "prefix/suffix are interpolated around the number"
    (let [e (render/fmt-text-expr "$%.2f" "$total")]
      (is (str/includes? e "\"$\""))
      (is (str/includes? e "toFixed(2)")))
    (is (str/includes? (render/fmt-text-expr "%.1f kg" "$w") "\" kg\""))))

(deftest fmt-spec-parsing
  (is (nil? (render/fmt-spec "no conversion here")))
  (is (= {:whole "%.2f" :pre "$" :digits "2" :type "f" :post ""}
         (render/fmt-spec "$%.2f")))
  (is (= {:whole "%d" :pre "" :digits "" :type "d" :post " items"}
         (render/fmt-spec "%d items"))))
