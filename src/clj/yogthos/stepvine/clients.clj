(ns yogthos.stepvine.clients
  "External service clients (stubs for v1).

   `:clients/patient` is a function `patient-id -> data-map` standing in for a
   real patient directory / FHIR lookup. Configured with an in-memory directory
   in system.edn; swap the function body for a real HTTP call later without
   changing the import machinery that consumes it."
  (:require
   [integrant.core :as ig]))

(defmethod ig/init-key :clients/patient
  [_ {:keys [directory]}]
  (fn lookup-patient [patient-id]
    (get directory patient-id)))
