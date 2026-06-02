(ns yogthos.stepvine.workflow
  "Declarative document state machine — pure building blocks (PLAN.md §15.10–15.12).

   A form declares a `:workflow`:

     {:initial :draft
      :states  {:draft  {:on {:submit :review}}
                :review {:on {:approve :done :reject :draft}}
                :done   {:terminal? true :locked? true}}
      :actions {:submit  {:guard :valid?
                          :steps [{:do :notify :message \"Submitted\"} {:do :snapshot}]}
                :approve {:require-role :reviewer :steps [...]}}}

   States + transitions are the machine; an action is a transition *label* plus
   optional effect **steps**. The transition target is implicit in the `:states`
   table — the runner prepends `[:set-state target]`, then the steps' directives.

   The orchestration (load → guard → branch → effects → commit / reject) is a
   **mycelium FSM** (`yogthos.stepvine.workflows.workflow` + `cells.workflow`);
   this namespace holds only the pure helpers those cells call, so the state
   graph is validated and the steps are computed without any I/O. Step `:fn`-free
   value resolution (`{:from path}` / `{:reaction id}`) keeps steps declarative;
   any expression evaluation would go through sci, never `clojure.core/eval`.")

;; --- graph -----------------------------------------------------------------

(defn validate
  "Validate a workflow graph at load: `:initial` must be a declared state and
   every transition target must be a declared state. Returns the workflow or
   throws."
  [{:keys [initial states] :as workflow}]
  (let [names (set (keys states))]
    (when-not (contains? names initial)
      (throw (ex-info "Workflow :initial is not a declared state"
                      {:initial initial :states names})))
    (doseq [[s {:keys [on]}] states
            [action target] on]
      (when-not (contains? names target)
        (throw (ex-info "Workflow transition targets an undeclared state"
                        {:state s :action action :target target :states names}))))
    workflow))

(defn initial-state [workflow] (:initial workflow))

(defn permitted?
  "Is `action` a legal transition from `state`?"
  [workflow state action]
  (contains? (get-in workflow [:states state :on]) action))

(defn target-state
  "The state `action` moves to from `state`, or nil if not permitted."
  [workflow state action]
  (get-in workflow [:states state :on action]))

(defn locked-state?
  "Is `state` read-only (documents in it reject edits)?"
  [workflow state]
  (boolean (get-in workflow [:states state :locked?])))

;; --- guards ----------------------------------------------------------------

(defn guard-ok?
  "True when the action's `:guard` reaction is truthy in ctx (or there is none)."
  [workflow action {:keys [rxns]}]
  (if-let [g (get-in workflow [:actions action :guard])]
    (boolean (get rxns g))
    true))

(defn role-ok?
  "True when the actor holds the action's `:require-role` (or it requires none).
   `ctx :roles` is the actor's role set; an admin satisfies any requirement."
  [workflow action {:keys [roles]}]
  (if-let [req (get-in workflow [:actions action :require-role])]
    (boolean (or (contains? (set roles) :admin) (contains? (set roles) req)))
    true))

;; --- step dispatcher (pluggable, §15.11) -----------------------------------

(defn resolve-value
  "Resolve a step value against the document context: `{:from [path]}` reads the
   document, `{:reaction id}` reads a reaction, anything else is a literal."
  [ctx v]
  (cond
    (and (map? v) (contains? v :from))     (get-in (:doc ctx) (:from v))
    (and (map? v) (contains? v :reaction)) (get-in ctx [:rxns (:reaction v)])
    :else                                  v))

(defmulti run-step
  "Compile an action step into a directive (or seq of directives). Dispatch on
   `:do` — adding a step kind is one defmethod (§15.11)."
  (fn [_ctx step] (:do step)))

(defmethod run-step :default [_ step]
  (throw (ex-info "Unknown workflow step" {:do (:do step) :step step})))

(defmethod run-step :notify   [ctx {:keys [message]}] [:notify (resolve-value ctx message)])
(defmethod run-step :snapshot [_ _]                   [:snapshot])
(defmethod run-step :pdf      [_ step]                [:pdf (select-keys step [:template])])
(defmethod run-step :set-field [ctx {:keys [path value]}] [:set-field path (resolve-value ctx value)])
(defmethod run-step :set-meta  [ctx {:keys [path value]}] [:set-meta path (resolve-value ctx value)])

(defn action-steps [workflow action] (get-in workflow [:actions action :steps]))

(defn action-directives
  "The directive list for running `action` from `state`: the implicit state
   transition first, then each step's directive(s)."
  [workflow state action ctx]
  (into [[:set-state (target-state workflow state action)]]
        (mapcat (fn [step]
                  (let [d (run-step ctx step)]
                    (if (and (sequential? d) (sequential? (first d))) d [d])))
                (action-steps workflow action))))
