(ns yogthos.stepvine.validation
  "Declarative validation vocabulary (PLAN.md §15.8).

   A model field declares `:validation` (and optionally `:validate-when`); this
   namespace compiles those into ordinary Domino **error reactions** plus a
   document-level `:valid?` reaction. Because validity then lives in the reactive
   graph, error/visibility state updates live over SSE with no extra round-trip
   (the reference re-runs a separate UI-rule engine); and the generated `:valid?`
   is exactly what a `submit` action gates on (§15.5 `:submit-when`).

   Vocabulary (each validator is a keyword or `[op & args]`):
     :required              non-blank
     [:min n] / [:max n]    numeric bounds
     :positive              > 0
     [:min-len n]/[:max-len n]  string length
     [:match re msg]        regex (over the string form)

   The generated reaction `:fn`s are quoted forms using only clojure.core, so they
   evaluate under the same sci sandbox as authored fns — never `clojure.core/eval`."
  (:require
   [clojure.string :as str]))

(defn- clause
  "A validator spec -> [failure-pred-form message]; the pred references symbol v."
  [spec]
  (let [[op & args] (if (sequential? spec) spec [spec])]
    (case op
      :positive [(list '<= 'v 0) (or (first args) "must be positive")]
      :min      (let [[n msg] args] [(list '< 'v n) (or msg (str "must be at least " n))])
      :max      (let [[n msg] args] [(list '> 'v n) (or msg (str "must be at most " n))])
      :min-len  (let [[n msg] args] [(list '< (list 'count (list 'str 'v)) n)
                                     (or msg (str "must be at least " n " characters"))])
      :max-len  (let [[n msg] args] [(list '> (list 'count (list 'str 'v)) n)
                                     (or msg (str "must be at most " n " characters"))])
      :match    (let [[re msg] args] [(list 'not (list 're-find (list 're-pattern re) (list 'str 'v)))
                                      (or msg "is not in the right format")])
      (throw (ex-info "Unknown validator" {:spec spec})))))

(defn- error-cond
  "A `cond` form returning the first failure message (or nil) for value `v`."
  [validations]
  (let [required? (boolean (some #(= % :required) validations))
        others    (remove #(= % :required) validations)]
    (list* 'cond
           (concat
            ;; blank: required -> message; optional -> nil (and skip the rest)
            [(list 'or (list 'nil? 'v) (list '= 'v "")) (when required? "is required")]
            (mapcat (fn [s] (let [[p m] (clause s)] [p m])) others)
            [:else nil]))))

(defn field-error-reaction
  "Generate the `<field>-error` reaction for a field's `:validation`, gated by an
   optional `:validate-when` guard reaction."
  [field-id validations validate-when]
  (let [body (error-cond validations)]
    {:id   (keyword (str (name field-id) "-error"))
     :args (if validate-when [field-id validate-when] [field-id])
     :fn   (if validate-when
             (list 'fn ['v 'guard] (list 'when 'guard body))
             (list 'fn ['v] body))}))

(defn valid-reaction
  "Generate the document `:valid?` reaction — true when every error is nil."
  [error-ids]
  (let [syms (mapv #(symbol (str "e" %)) (range (count error-ids)))]
    {:id   :valid?
     :args (vec error-ids)
     :fn   (list 'fn syms (list* 'and (map #(list 'nil? %) syms)))}))

(defn- model-validations
  "Seq of [field-id validations validate-when] for top-level fields declaring
   `:validation`."
  [model]
  (keep (fn [entry]
          (let [opts (second entry)]
            (when (and (map? opts) (:validation opts))
              [(:id opts) (:validation opts) (:validate-when opts)])))
        model))

(defn compile-validations
  "Return `form` with generated error reactions + a `:valid?` reaction appended to
   `:data :reactions`. No-op when no field declares `:validation`."
  [form]
  (let [specs (model-validations (get-in form [:data :model]))]
    (if (empty? specs)
      form
      (let [errs   (mapv (fn [[fid vs vw]] (field-error-reaction fid vs vw)) specs)
            valid  (valid-reaction (map :id errs))
            extant (vec (get-in form [:data :reactions]))]
        (assoc-in form [:data :reactions]
                  (into (into extant errs) [valid]))))))
