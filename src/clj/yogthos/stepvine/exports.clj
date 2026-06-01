(ns yogthos.stepvine.exports
  "Templated document exports.

   A form may declare `:exports {<id> {:label .. :template ..}}`. The template is
   arbitrary data with placeholders that are substituted from the document's
   current field + reaction values:

     - any keyword that is a field id or reaction id -> that value
     - :fn/uuid -> a fresh uuid string
     - :fn/date -> the current instant

   This is an `:exports`/`publish-export` concept (FHIR-templated output) with a
   small, transport-agnostic substitution pass."
  (:require
   [yogthos.stepvine.editor.impl :as impl])
  (:import
   [java.time Instant]
   [java.util UUID]))

(defn export-values
  "Map of {field-or-reaction-id -> current value} for substitution."
  [session]
  (let [field-ids (keys (:field-opts session))
        rxn-ids   (map :id (get-in session [:form :data :reactions]))]
    (into {} (map (fn [id] [id (impl/value session id)])) (concat field-ids rxn-ids))))

(defn substitute
  "Walk `template`, replacing placeholders from `values` in value positions only
   (map keys are preserved)."
  [template values]
  (letfn [(sub [node]
            (cond
              (= node :fn/uuid)                             (str (UUID/randomUUID))
              (= node :fn/date)                             (str (Instant/now))
              (and (keyword? node) (contains? values node)) (get values node)
              (map? node)    (reduce-kv (fn [m k v] (assoc m k (sub v))) {} node)
              (vector? node) (mapv sub node)
              (seq? node)    (map sub node)
              :else          node))]
    (sub template)))

(defn render-export
  "Produce the export named `export-id` for a live session, or nil if undefined."
  [session export-id]
  (when-let [export (get-in session [:form :exports (keyword export-id)])]
    (substitute (:template export) (export-values session))))
