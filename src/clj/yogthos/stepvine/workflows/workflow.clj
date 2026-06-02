(ns yogthos.stepvine.workflows.workflow
  "The document workflow as a mycelium FSM (PLAN.md §15.10).

   States (cells): load → guard → effects → commit, with a guarded branch to
   reject. The branch is a mycelium `:dispatches` predicate over the data
   (`:permitted?`); `:default` routes the denied case. So the action-execution
   machine is a genuine mycelium state machine, and the document state transition
   it performs is driven from the form's declarative `:workflow` table."
  (:require
   [mycelium.core :as myc]
   yogthos.stepvine.cells.workflow))

(def run-action
  (myc/pre-compile
   {:cells {:start   :wf/parse
            :load    :wf/load
            :guard   :wf/guard
            :effects :wf/effects
            :commit  :wf/commit
            :reject  :wf/reject}
    :edges {:start   :load
            :load    :guard
            :guard   {:ok :effects :default :reject}   ; the state-machine branch
            :effects :commit
            :commit  :end
            :reject  :end}
    :dispatches {:guard [[:ok (fn [d] (:permitted? d))]]}}))
