(ns yogthos.stepvine.builder
  "Visual form builder.

   The builder is itself a Stepvine form-document (forms/builder.edn): top-level
   fields for the new form's id/title plus a collection of field definitions
   ({:fid :flabel :ftype}). `build-form` reads that document's live state and
   generates a real form definition (Domino model + a default view), which is
   then persisted with forms/save-form!. So the builder is built with the same
   engine it produces — collections, reactivity and all."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.editor.impl :as impl]))

(defn- ordered-fields
  "The field-definition rows from the builder document's :fields collection, in
   order, keeping only rows with a non-blank field id."
  [session]
  (let [{:keys [order items]} (get (signals/collections-data session) :fields)]
    (->> order
         (map items)
         (filter (fn [{:keys [fid]}] (not (str/blank? fid)))))))

(defn build-form
  "Generate a form definition map from the builder document's current state.
   Returns nil if no form id was given."
  [session]
  (let [form-id (impl/value session :form-id)
        title   (impl/value session :form-title)]
    (when-not (str/blank? form-id)
      (let [fields (ordered-fields session)
            model  (vec (for [{:keys [fid ftype]} fields]
                          [(keyword fid) {:id   (keyword fid)
                                          :type (keyword (if (str/blank? ftype) "string" ftype))}]))
            markup (into [:c/form {} [:h1 (or title form-id)]]
                         (for [{:keys [fid flabel]} fields]
                           [:c/input-field {:label (or flabel fid) :id (keyword fid)}]))]
        {:id      (keyword form-id)
         :title   (or title form-id)
         :version 1
         :data    {:model model}
         :views   {:default {:title (or title form-id)
                             :opts  {:widget-namespaces {"c" "stepvine.components"}}
                             :markup markup}}}))))
