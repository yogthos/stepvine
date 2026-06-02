(ns yogthos.stepvine.pdf
  "PDF report generation (PLAN.md §15.13) via clj-pdf.

   Two ways to render a document to a PDF body (a clj-pdf element vector):
     - the default — title + a table of the document's field/reaction values;
     - a form-declared template — a clj-pdf body with `{:field id}` / `{:reaction
       id}` placeholders substituted from the live document.

   `write!`/`generate` turn a body into a file/bytes. Used by the `:pdf` workflow
   step (directives layer) and served by the report download route."
  (:require
   [clj-pdf.core :as pdf]
   [clojure.java.io :as io]
   [clojure.walk :as walk]))

(defn- scalar-fields
  "[[field-id label] …] for top-level scalar model fields, in declaration order."
  [form]
  (keep (fn [entry]
          (let [opts (second entry)]
            (when (and (map? opts) (:type opts))
              [(:id opts) (or (:label opts) (name (:id opts)))])))
        (get-in form [:data :model])))

(defn document->spec
  "A default clj-pdf body for a document: a heading + a field/value table."
  [form values _reactions]
  (let [title (or (:title form) (name (:id form)))]
    [{:title title}
     [:heading title]
     [:spacer]
     (into [:table {:header ["Field" "Value"] :width-percent 100}]
           (for [[id label] (scalar-fields form)]
             [(str label) (str (get values id ""))]))]))

(defn substitute
  "Replace `{:field id}` / `{:reaction id}` placeholders in a clj-pdf template
   with the document's current values."
  [template values reactions]
  (walk/postwalk
   (fn [x]
     (cond
       (and (map? x) (contains? x :field))    (str (get values (:field x) ""))
       (and (map? x) (contains? x :reaction)) (str (get reactions (:reaction x) ""))
       :else x))
   template))

(defn generate
  "Render a clj-pdf body `spec` to PDF bytes."
  [spec]
  (let [out (java.io.ByteArrayOutputStream.)]
    (pdf/pdf spec out)
    (.toByteArray out)))

(defn write!
  "Render `spec` to a PDF `file` (creating parent directories). Returns the file."
  [spec file]
  (io/make-parents file)
  (with-open [out (io/output-stream file)]
    (pdf/pdf spec out))
  file)
