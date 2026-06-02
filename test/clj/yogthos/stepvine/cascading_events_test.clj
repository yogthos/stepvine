(ns yogthos.stepvine.cascading-events-test
  "Cascading derived fields: a Domino :event whose input is another event's
   output re-fires when that output changes, so one edit ripples through the
   whole chain. Exercises the `order` example form:
       qty, price ─▶ subtotal ─▶ discount ─▶ tax ─▶ total"
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.editor.impl :as impl]))

(defn- approx [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest order-cascade
  (let [form (forms/load-form "order")
        base (impl/apply-changes (impl/create-session form {})
                                 [[:qty 3] [:price 20] [:discount-pct 10] [:tax-rate 8]])]
    (testing "the full chain computes from the inputs"
      (is (approx 60   (impl/value base :subtotal)))   ; 3 × 20
      (is (approx 6    (impl/value base :discount)))    ; 60 × 10%
      (is (approx 4.32 (impl/value base :tax)))         ; (60 − 6) × 8%
      (is (approx 58.32 (impl/value base :total))))     ; 60 − 6 + 4.32

    (testing "changing ONE input cascades through every downstream calculated field"
      ;; bump qty 3 → 6: subtotal, then discount, then tax, then total all recompute
      (let [s (impl/apply-changes base [[:qty 6]])]
        (is (approx 120    (impl/value s :subtotal)))   ; 6 × 20
        (is (approx 12     (impl/value s :discount)))   ; recomputed from new subtotal
        (is (approx 8.64   (impl/value s :tax)))        ; recomputed from new subtotal/discount
        (is (approx 116.64 (impl/value s :total)))))    ; recomputed from all three

    (testing "a mid-chain input ripples only forward (subtotal unchanged, tax/total move)"
      ;; change the tax rate: subtotal & discount are upstream and stay put;
      ;; tax and total recompute
      (let [s (impl/apply-changes base [[:tax-rate 0]])]
        (is (approx 60 (impl/value s :subtotal)))       ; upstream — unchanged
        (is (approx 6  (impl/value s :discount)))       ; upstream — unchanged
        (is (approx 0  (impl/value s :tax)))            ; (60 − 6) × 0%
        (is (approx 54 (impl/value s :total)))))        ; 60 − 6 + 0

    (testing "a reaction tracks a calculated field across the cascade"
      (is (false? (impl/value base :free-shipping?)))   ; subtotal 60 < 100
      (let [s (impl/apply-changes base [[:qty 6]])]     ; subtotal 120 ≥ 100
        (is (true? (impl/value s :free-shipping?)))))))
