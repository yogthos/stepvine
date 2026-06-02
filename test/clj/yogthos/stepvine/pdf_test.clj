(ns yogthos.stepvine.pdf-test
  "§15.13: PDF report generation via clj-pdf."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [yogthos.stepvine.pdf :as pdf]))

(def form
  '{:id :ticket :title "Support Ticket"
    :data {:model [[:title  {:id :title  :type :string :label "Title"}]
                   [:detail {:id :detail :type :string :label "Detail"}]]}})

(defn- pdf-bytes? [^bytes b]
  (and (> (count b) 100)
       (= "%PDF" (subs (String. b 0 8 "ISO-8859-1") 0 4))))

(deftest default-document-spec-renders-fields
  (let [spec (pdf/document->spec form {:title "Printer down" :detail "It's on fire"} {})]
    (testing "the body carries the title heading and field/value rows"
      (is (= "Support Ticket" (-> spec (nth 1) second)))     ; [:heading "Support Ticket"]
      (let [flat (str spec)]
        (is (clojure.string/includes? flat "Printer down"))
        (is (clojure.string/includes? flat "It's on fire"))))
    (testing "the spec generates valid PDF bytes"
      (is (pdf-bytes? (pdf/generate spec))))))

(deftest template-substitution
  (let [tmpl [{:title "T"} [:heading "Ticket"] [:paragraph {:field :title}]
              [:paragraph {:reaction :greeting}]]
        out  (pdf/substitute tmpl {:title "Printer down"} {:greeting "Hi"})]
    (is (= [:paragraph "Printer down"] (nth out 2)))
    (is (= [:paragraph "Hi"] (nth out 3)))
    (is (pdf-bytes? (pdf/generate out)))))

(deftest write-creates-a-pdf-file
  (let [f (io/file "data" "test-reports" "out.pdf")]
    (.delete f)
    (pdf/write! (pdf/document->spec form {:title "x"} {}) f)
    (is (.exists f))
    (is (pdf-bytes? (java.nio.file.Files/readAllBytes (.toPath f))))
    (.delete f)))
