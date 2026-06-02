(ns yogthos.stepvine.terminology
  "Terminology / value-set expansion for coded fields (parity stepvine-9ox).

   Clinical forms bind coded fields to a value set rather than an ad-hoc option
   list. `expand` turns a FHIR ValueSet — either a `$expand` response
   (`{:expansion {:contains [{:system :code :display} …]}}`) or a compact
   authoring form (`{:system … :concepts [{:code :display} …]}`) — into stepvine
   coded options `{:value <code> :label <display> :system <code-system>}`.

   The `:value` is the FHIR code that is stored; the `:label` is the human display
   shown in the widget; `:system` names the code system the code belongs to."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- concept->option [system {:keys [code display] :as c}]
  {:value  code
   :label  (or display code)
   :system (or (:system c) system)})

(defn expand
  "Expand a FHIR ValueSet into a vector of coded options. Accepts a `$expand`
   response (`:expansion`/`:contains`), or a compact `{:system :concepts}` /
   `{:system :concept}` authoring form. Returns `[]` for an empty/unknown shape."
  [valueset]
  (let [system   (:system valueset)
        contains (or (get-in valueset [:expansion :contains])
                     (:concepts valueset)
                     (:concept valueset)
                     [])]
    (mapv (partial concept->option system) contains)))

(defn- edn-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".edn")))

(defn load-dir
  "Load a directory of ValueSet EDN files into `{value-set-id -> coded-options}`,
   expanding each. A missing directory yields `{}` (terminology is optional)."
  [dir]
  (let [d (io/file dir)]
    (if (.isDirectory d)
      (into {}
            (map (fn [f] (let [m (edn/read-string (slurp f))] [(:id m) (expand m)])))
            (filter edn-file? (.listFiles d)))
      {})))
