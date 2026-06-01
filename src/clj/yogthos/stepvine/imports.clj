(ns yogthos.stepvine.imports
  "External-dependency hydration.

   A form may declare `:imports`, each with a `:trigger` field and a `:mapping`
   of {target-field-id -> path-in-service-data}. When the trigger field changes,
   the service is queried and the mapped values are transacted into the document
   (which recomputes + broadcasts like any other change). Pure helpers here; the
   wiring lives in cells.form.")

(defn import-for-trigger
  "The import config whose :trigger is `field-id`, or nil."
  [form-raw field-id]
  (some (fn [[_ cfg]] (when (= (:trigger cfg) field-id) cfg))
        (:imports form-raw)))

(defn mapped-changes
  "Turn a service `data` map + a `mapping` into `[[field-id value] ...]` changes,
   skipping fields the service didn't provide."
  [data mapping]
  (when data
    (into []
          (keep (fn [[field-id path]]
                  (let [v (get-in data (if (vector? path) path [path]))]
                    (when (some? v) [field-id v]))))
          mapping)))
