(ns yogthos.stepvine.workflow-test
  "Phase 10 (§15.10–15.12): the declarative state machine's pure building blocks —
   graph validation, transition lookup, and the pluggable step→directive
   dispatcher. The orchestration itself is a mycelium FSM (workflow_fsm_test)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.workflow :as wf]))

(def flow
  {:initial :draft
   :states  {:draft  {:on {:submit :review}}
             :review {:on {:approve :done :reject :draft}}
             :done   {:terminal? true :locked? true}}
   :actions {:submit  {:guard :valid?
                       :steps [{:do :notify :message "Submitted for review"}
                               {:do :snapshot}]}
             :approve {:require-role :reviewer
                       :steps [{:do :set-meta :path [:reviewed-by] :value {:from [:name]}}]}}})

(deftest validate-rejects-malformed-graphs
  (testing "a transition to an undeclared state is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (wf/validate {:initial :a :states {:a {:on {:go :nowhere}}}}))))
  (testing "an undeclared :initial is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (wf/validate {:initial :z :states {:a {}}}))))
  (testing "a well-formed graph validates and returns itself"
    (is (= flow (wf/validate flow)))))

(deftest transitions-and-locking
  (is (wf/permitted? flow :draft :submit))
  (is (not (wf/permitted? flow :draft :approve)))      ; not legal from :draft
  (is (= :review (wf/target-state flow :draft :submit)))
  (is (nil? (wf/target-state flow :draft :approve)))
  (is (wf/locked-state? flow :done))
  (is (not (wf/locked-state? flow :review)))
  (is (= :draft (wf/initial-state flow))))

(deftest guards-and-roles
  (testing "an action with a :guard reaction is gated on its truthiness"
    (is (wf/guard-ok? flow :submit {:rxns {:valid? true}}))
    (is (not (wf/guard-ok? flow :submit {:rxns {:valid? false}}))))
  (testing "an action with no :guard is always ok"
    (is (wf/guard-ok? flow :approve {})))
  (testing "role requirement (actor holds a role set; admin satisfies any)"
    (is (wf/role-ok? flow :approve {:roles #{:reviewer}}))
    (is (wf/role-ok? flow :approve {:roles #{:admin}}))
    (is (not (wf/role-ok? flow :approve {:roles #{:nurse}})))
    (is (wf/role-ok? flow :submit {:roles #{}}))))      ; no :require-role

(deftest run-step-emits-directives
  (let [ctx {:values {} :rxns {} :doc {:name "Ada"}}]
    (is (= [:notify "hi"]    (wf/run-step ctx {:do :notify :message "hi"})))
    (is (= [:snapshot]       (wf/run-step ctx {:do :snapshot})))
    (is (= [:set-field [:x] 1] (wf/run-step ctx {:do :set-field :path [:x] :value 1})))
    (testing "values resolve against the document (:from path)"
      (is (= [:set-meta [:reviewed-by] "Ada"]
             (wf/run-step {:doc {:name "Ada"}}
                          {:do :set-meta :path [:reviewed-by] :value {:from [:name]}}))))))

(deftest action-directives-prepend-the-transition
  (testing "the state transition is implicit (from the table), prepended to steps"
    (let [ctx {:doc {:name "Ada"}}
          ds  (wf/action-directives flow :draft :submit ctx)]
      (is (= [:set-state :review] (first ds)))
      (is (some #(= % [:notify "Submitted for review"]) ds))
      (is (some #(= % [:snapshot]) ds)))))

(deftest conditional-step-values
  (let [ctx {:rxns {:urgent? true} :doc {:to "a@b.c"}}]
    (testing ":cond picks the first truthy test's value (bare keyword = reaction)"
      (is (= "urgent@x" (wf/resolve-value ctx {:cond [:urgent? "urgent@x" :else "ops@x"]})))
      (is (= "ops@x"    (wf/resolve-value (assoc-in ctx [:rxns :urgent?] false)
                                          {:cond [:urgent? "urgent@x" :else "ops@x"]})))
      (is (nil?         (wf/resolve-value (assoc-in ctx [:rxns :urgent?] false)
                                          {:cond [:urgent? "urgent@x"]}))))     ; no match, no :else
    (testing "tests + values can themselves be value-specs"
      (is (= "a@b.c" (wf/resolve-value ctx {:cond [{:reaction :urgent?} {:from [:to]} :else "x"]}))))))

(deftest step-when-gates-execution
  (let [wf '{:initial :open :states {:open {:on {:submit :done}} :done {}}
             :actions {:submit {:steps [{:do :notify :message "always"}
                                        {:do :notify :message "only-urgent" :when :urgent?}]}}}]
    (testing "a step gated by a falsy :when is skipped"
      (is (= [[:set-state :done] [:notify "always"]]
             (wf/action-directives wf :open :submit {:rxns {:urgent? false}}))))
    (testing "a step whose :when is truthy runs"
      (is (= [[:set-state :done] [:notify "always"] [:notify "only-urgent"]]
             (wf/action-directives wf :open :submit {:rxns {:urgent? true}}))))))
