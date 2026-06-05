(ns yogthos.stepvine.bind-test
  "The shared field bind/lock datastar attrs (components.bind)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.components.bind :as bind]))

(def ^:private ctx {:doc-id "d1"})

(deftest lock-attrs-wires-focus-blur-disabled
  (let [a (bind/lock-attrs ctx :kg "kg")]
    (testing "focus acquires + blur releases the field lock"
      (is (= "@post('/doc/d1/field/kg/lock')"   (get a "data-on:focus")))
      (is (= "@post('/doc/d1/field/kg/unlock')" (get a "data-on:blur"))))
    (testing "disabled while a different user holds the lock"
      (is (= "!!$locks.kg && $locks.kg != $uid" (get a "data-attr:disabled"))))
    (testing "only the three lock attrs — no edit event"
      (is (= #{"data-on:focus" "data-on:blur" "data-attr:disabled"} (set (keys a)))))))

(deftest edit-bind-attrs-adds-the-edit-post
  (let [a (bind/edit-bind-attrs ctx :kg "kg" "data-on:input__debounce.300ms")]
    (testing "carries the lock trio"
      (is (= "@post('/doc/d1/field/kg/lock')"   (get a "data-on:focus")))
      (is (= "@post('/doc/d1/field/kg/unlock')" (get a "data-on:blur")))
      (is (= "!!$locks.kg && $locks.kg != $uid" (get a "data-attr:disabled"))))
    (testing "plus the edit POST under the given event key"
      (is (= "@post('/doc/d1/field/kg')" (get a "data-on:input__debounce.300ms"))))
    (testing "a debounced text input ALSO flushes on change (blur) so navigating
              away before the debounce fires doesn't drop the value"
      (is (= "@post('/doc/d1/field/kg')" (get a "data-on:change"))))))

(deftest non-input-edit-event-has-no-extra-change-flush
  ;; widgets whose edit event is already :change (dropdown/checkbox) don't get a
  ;; duplicate change handler.
  (let [a (bind/edit-bind-attrs ctx :pick "pick" "data-on:change")]
    (is (= "@post('/doc/d1/field/pick')" (get a "data-on:change")))
    (is (= #{"data-on:focus" "data-on:blur" "data-attr:disabled" "data-on:change"} (set (keys a))))))

(deftest edit-event-key-is-configurable
  (is (contains? (bind/edit-bind-attrs ctx :pick "pick" "data-on:change")
                 "data-on:change"))
  (testing "item-scoped signal feeds the disabled guard verbatim"
    (is (= "!!$locks.members_0_name && $locks.members_0_name != $uid"
           (get (bind/lock-attrs ctx :name "members_0_name") "data-attr:disabled")))))
