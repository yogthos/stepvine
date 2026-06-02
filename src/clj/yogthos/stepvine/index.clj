(ns yogthos.stepvine.index
  "Index lookups (PLAN.md §15.13) — resolve an external key into an entity, the
   read-side mirror of sources/imports.

   A form's `:index` declares how a new document is *started from a key* (e.g. a
   patient MRN): the key is validated by a lookup, seeded into a field (`:into`),
   and the form's `:imports` map the matching entity into the document. The
   lookup itself reuses the unified source resolver, so any source `:kind`
   (`:client`, `:http`) is an index backend.

   `:index` spec: {:kind :client :client :patient :key :mrn   ; a source spec
                   :into :patient-id                          ; field the key seeds
                   :prompt \"Patient MRN\" :preview [:given :family]}"
  (:require
   [yogthos.stepvine.sources :as sources]))

(defn lookup
  "Resolve `key` to an entity via the form's `:index` spec (a source spec).
   Returns `{:found? :entity :key}`."
  [ctx spec key]
  (let [resolve (sources/resolve-source ctx spec)
        entity  (resolve {(:key spec) key})]
    {:found? (boolean (and (map? entity) (seq entity)))
     :entity (when (map? entity) entity)
     :key    key}))

(defn preview
  "[[label value] …] for the entity fields named in `paths`, for a lookup preview."
  [entity paths]
  (mapv (fn [p] [(name p) (str (get entity p ""))]) paths))
