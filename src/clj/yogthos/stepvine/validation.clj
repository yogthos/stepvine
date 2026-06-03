(ns yogthos.stepvine.validation
  "Declarative validation vocabulary (PLAN.md §15.8).

   A model field declares `:validation` (and optionally `:validate-when`); this
   namespace compiles those into ordinary Domino **error reactions** plus a
   document-level `:valid?` reaction. Because validity then lives in the reactive
   graph, error/visibility state updates live over SSE with no extra round-trip
   (the reference re-runs a separate UI-rule engine); and the generated `:valid?`
   is exactly what a `submit` action gates on (§15.5 `:submit-when`).

   Vocabulary (each validator is a keyword or `[op & args]`):
     :required                 non-blank
     [:min n] / [:max n]       numeric bounds
     :positive                 > 0
     [:min-len n]/[:max-len n] string length
     [:match re msg]           regex (over the string form)
     :digits-only              only 0-9
     [:contains x]             an :array field includes x
     [:before f] / [:after f]  this (date) field is before/after field `f`
     [:field-must-equal f]     this field equals field `f` (e.g. confirm email)

   The cross-field validators reference another field `f` (a bare id, or
   `{:path-key f}`); that field's value is threaded in as an extra reaction arg.
   The generated reaction `:fn`s are quoted forms using only clojure.core, so they
   evaluate under the same sci sandbox as authored fns — never `clojure.core/eval`.")

(defn- ref-field
  "The field id a cross-field validator references: a bare keyword, or
   `{:path-key f}` / `{:field f}`."
  [other]
  (if (map? other) (or (:path-key other) (:field other)) other))

(defn- clause
  "A validator spec -> `[failure-pred-form message ref-field-ids]`. The pred
   references symbol `v` (this field) and, for cross-field validators, a symbol
   named after the referenced field; `ref-field-ids` lists those so the reaction
   takes them as extra args."
  [spec]
  (let [[op & args] (if (sequential? spec) spec [spec])
        ref         (fn [other] (let [o (ref-field other)] [o (symbol (name o))]))]
    (case op
      :positive [(list '<= 'v 0) (or (first args) "must be positive") nil]
      :min      (let [[n msg] args] [(list '< 'v n) (or msg (str "must be at least " n)) nil])
      :max      (let [[n msg] args] [(list '> 'v n) (or msg (str "must be at most " n)) nil])
      :min-len  (let [[n msg] args] [(list '< (list 'count (list 'str 'v)) n)
                                     (or msg (str "must be at least " n " characters")) nil])
      :max-len  (let [[n msg] args] [(list '> (list 'count (list 'str 'v)) n)
                                     (or msg (str "must be at most " n " characters")) nil])
      :match    (let [[re msg] args] [(list 'not (list 're-find (list 're-pattern re) (list 'str 'v)))
                                      (or msg "is not in the right format") nil])
      :digits-only [(list 'boolean (list 're-find (list 're-pattern "[^0-9]") (list 'str 'v)))
                    (or (first args) "must contain only digits") nil]
      :contains (let [[x msg] args] [(list 'not (list 'some (set [x]) 'v))
                                     (or msg (str "must include " x)) nil])
      :before   (let [[other msg] args [o s] (ref other)]
                  [(list 'and s (list 'not (list 'neg? (list 'compare (list 'str 'v) (list 'str s)))))
                   (or msg (str "must be before " (name o))) [o]])
      :after    (let [[other msg] args [o s] (ref other)]
                  [(list 'and s (list 'not (list 'pos? (list 'compare (list 'str 'v) (list 'str s)))))
                   (or msg (str "must be after " (name o))) [o]])
      :field-must-equal (let [[other msg] args [o s] (ref other)]
                          [(list 'not= (list 'str 'v) (list 'str s))
                           (or msg (str "must match " (name o))) [o]])
      (throw (ex-info "Unknown validator" {:spec spec})))))

(defn- error-cond
  "A `cond` form returning the first failure message (or nil), from pre-computed
   `clauses` (`[pred msg refs]`)."
  [clauses required?]
  (list* 'cond
         (concat
          ;; blank: required -> message; optional -> nil (and skip the rest)
          [(list 'or (list 'nil? 'v) (list '= 'v "")) (when required? "is required")]
          (mapcat (fn [[p m _]] [p m]) clauses)
          [:else nil])))

(defn field-error-reaction
  "Generate the `<field>-error` reaction for a field's `:validation`, threading in
   any cross-field references and an optional `:validate-when` guard reaction."
  [field-id validations validate-when]
  (let [required? (boolean (some #{:required} validations))
        clauses   (mapv clause (remove #{:required} validations))
        refs      (vec (distinct (mapcat (fn [[_ _ r]] r) clauses)))
        ref-syms  (mapv (comp symbol name) refs)
        body      (error-cond clauses required?)
        params    (vec (concat ['v] ref-syms (when validate-when ['guard])))
        args      (vec (concat [field-id] refs (when validate-when [validate-when])))]
    {:id   (keyword (str (name field-id) "-error"))
     :args args
     :fn   (list 'fn params (if validate-when (list 'when 'guard body) body))}))

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
