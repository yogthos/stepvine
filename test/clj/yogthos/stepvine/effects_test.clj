(ns yogthos.stepvine.effects-test
  "Engine-emitted effects (parity stepvine-9d8): events change document data, the
   Domino engine emits effect *intents* when data changes, and the host layer
   performs them (email / notify / import)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.effects :as effects]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.http :as http]
   [yogthos.stepvine.imports :as imports]
   [yogthos.stepvine.mailer :as mailer]
   [yogthos.stepvine.workflow-rules :as workflow]))

;; --- the engine emits intents, change-triggered + conditional --------------

(def ^:private order-form
  '{:id :order :version 1
    :data {:model   [[:total {:id :total :type :number}]]
           :effects [{:on [:total]
                      :handler (fn [{:keys [inputs]}]
                                 (when (> (or (:total inputs) 0) 100)
                                   {:kind :notify :message "big"}))}]}
    :views {:default {:opts {} :markup [:div]}}})

(deftest engine-emits-effect-intents
  (let [s (impl/create-session order-form {})]
    (testing "no emission on initialize"
      (is (empty? (impl/emitted-effects s))))
    (testing "below the guard, the effect fires but emits nothing"
      (is (empty? (impl/emitted-effects (impl/apply-changes s [[:total 50]])))))
    (testing "above the guard, the engine emits the intent"
      (is (= [{:kind :notify :message "big"}]
             (impl/emitted-effects (impl/apply-changes s [[:total 250]])))))))

(def ^:private derived-form
  ;; total is DERIVED (an event), so its effect fires only on a real change —
  ;; the real-world case (vs. a directly-set field, which is in the change-history
  ;; on every transact even at the same value)
  '{:id :order :version 1
    :data {:model   [[:qty {:id :qty :type :number}] [:total {:id :total :type :number}]]
           :events  [{:id :calc :inputs [:qty] :outputs [:total]
                      :handler (fn [{{:keys [qty]} :inputs}] {:total (* (or qty 0) 100)})}]
           :effects [{:on [:total]
                      :handler (fn [{:keys [inputs]}]
                                 (when (> (or (:total inputs) 0) 100) {:kind :notify}))}]}
    :views {:default {:opts {} :markup [:div]}}})

(deftest derived-trigger-fires-only-on-real-change
  (let [s  (impl/apply-changes (impl/create-session derived-form {}) [[:qty 3]])] ; total 300 > 100
    (is (= [{:kind :notify}] (impl/emitted-effects s)))
    (testing "re-setting qty to the SAME value doesn't move total -> no emission"
      (is (empty? (impl/emitted-effects (impl/apply-changes s [[:qty 3]])))))))

;; --- imports are layered on the effect signal ------------------------------

(deftest imports-compile-to-effects
  (let [form {:imports [{:on #{:event/patient-id} :from :patient}
                        {:on #{:create} :from :seed}]}      ; :create is not an effect
        effs (imports/->effects form)]
    (is (= 1 (count effs)) "only the event-triggered import becomes an effect")
    (is (= [:patient-id] (:on (first effs))))
    (testing "its handler emits an :import intent for the trigger fields"
      (let [h (eval (:handler (first effs)))]              ; quoted (fn [_] …)
        (is (= {:kind :import :fields [:patient-id]} (h nil)))))))

;; --- the host performs intents (dispatch on :kind) -------------------------

(deftest host-performs-intents
  (testing ":email -> the mailer"
    (let [mbox (mailer/recording)]
      (effects/perform-all! {:mailer mbox} "doc1" {}
                            [{:kind :email :to "a@b.c" :subject "hi" :body "yo"}])
      (is (= 1 (count (mailer/outbox mbox))))
      (is (= "a@b.c" (:to (first (mailer/outbox mbox)))))))
  (testing "an unknown kind is ignored (no throw)"
    (is (nil? (effects/perform-all! {} "doc1" {} [{:kind :mystery}])))))

;; --- the :http workflow step (external service call) -----------------------

(deftest http-step-resolves-and-guards
  (testing "run-step :http resolves the body map from the document"
    (is (= [:http {:url "https://h.example.com/x" :host-allow ["h.example.com"]
                   :body {:event "closed" :title "Printer down"}}]
           (workflow/run-step {:doc {:title "Printer down"}}
                              {:do :http :url "https://h.example.com/x"
                               :host-allow ["h.example.com"]
                               :body {:event "closed" :title {:from [:title]}}}))))
  (testing "perform! :http calls the client for an allowlisted host"
    (let [c (http/recording)]
      (effects/perform! {:http-client c} "d1" {}
                        {:kind :http :url "https://h.example.com/x" :host-allow ["h.example.com"] :body {:a 1}})
      (is (= 1 (count (http/recorded c))))
      (is (= "https://h.example.com/x" (:url (first (http/recorded c)))))))
  (testing "perform! :http BLOCKS a host not on the allowlist (SSRF guard)"
    (let [c (http/recording)]
      (effects/perform! {:http-client c} "d1" {}
                        {:kind :http :url "https://evil.example/x" :host-allow ["h.example.com"] :body {:a 1}})
      (is (empty? (http/recorded c))))))
