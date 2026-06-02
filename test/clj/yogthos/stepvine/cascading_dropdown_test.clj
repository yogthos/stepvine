(ns yogthos.stepvine.cascading-dropdown-test
  "Parity (stepvine-9wo): cascading / dependent dropdowns. A dropdown that
   :depends-on a parent field shows only the options whose :when matches the
   parent's current value; the server finds such dropdowns to re-render them when
   the parent changes."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.cascades :as cascades]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.render :as render]))

(def ^:private aliases {"c" "stepvine.components"})

(def ^:private clinic-node
  [:c/dropdown-select {:id :clinic :label "Clinic" :depends-on :region
                       :options [{:value "north-general" :label "North General" :when "north"}
                                 {:value "south-bay"     :label "South Bay"     :when "south"}]}])

(def ^:private markup
  [:c/form {}
   [:c/dropdown-select {:id :region :options [["North" "north"] ["South" "south"]]}]
   clinic-node])

(defn- ctx [values]
  {:values values :aliases aliases :field-opts {} :rxns {}})

(deftest finds-dependent-dropdowns
  (testing "the clinic dropdown is found as a dependent of region"
    (let [deps (render/dependent-dropdown-nodes markup aliases :region)]
      (is (= 1 (count deps)))
      (is (= :clinic (:id (first deps))))
      (is (= clinic-node (:node (first deps))))))
  (testing "nothing depends on an unrelated field"
    (is (empty? (render/dependent-dropdown-nodes markup aliases :reason)))))

(deftest options-filter-by-parent-value
  (testing "with region=north only the north clinic renders"
    (let [html (render/render-view (ctx {:region "north"}) clinic-node)]
      (is (str/includes? html "North General"))
      (is (not (str/includes? html "South Bay")))))
  (testing "with region=south only the south clinic renders"
    (let [html (render/render-view (ctx {:region "south"}) clinic-node)]
      (is (str/includes? html "South Bay"))
      (is (not (str/includes? html "North General")))))
  (testing "with no region chosen, no region-tagged option renders (empty child)"
    (let [html (render/render-view (ctx {}) clinic-node)]
      (is (not (str/includes? html "North General")))
      (is (not (str/includes? html "South Bay")))))
  (testing "a dependent dropdown carries a field-<id> wrapper id for morphing"
    (is (str/includes? (render/render-view (ctx {:region "north"}) clinic-node)
                       "id=\"field-clinic\""))))

;; --- the cascade follows the dependency graph to FULL depth ---------------

(def ^:private chain-markup
  ;; region -> clinic -> department  (a three-level :depends-on chain)
  [:c/form {}
   [:c/dropdown-select {:id :region :options [["North" "north"]]}]
   [:c/dropdown-select {:id :clinic :depends-on :region
                        :options [{:value "ng" :when "north"}]}]
   [:c/dropdown-select {:id :department :depends-on :clinic
                        :options [{:value "cardio" :when "ng"}]}]])

(deftest cascade-closure-reaches-every-descendant
  (testing "a region change ripples to clinic AND department (transitively)"
    (let [ids (map :id (render/cascade-closure chain-markup aliases :region))]
      (is (= [:clinic :department] ids))))            ; breadth-first, full depth
  (testing "a mid-chain change ripples only to what's below it"
    (is (= [:department] (map :id (render/cascade-closure chain-markup aliases :clinic)))))
  (testing "a leaf change ripples to nothing"
    (is (empty? (render/cascade-closure chain-markup aliases :department))))
  (testing "an unrelated field has no dependents"
    (is (empty? (render/cascade-closure chain-markup aliases :reason)))))

;; --- the VALUE cascade rides Domino's event DAG (clearing events) ----------

(def ^:private chain-form
  {:id :booking :version 1
   :data {:model [[:region     {:id :region     :type :string}]
                  [:clinic     {:id :clinic     :type :string}]
                  [:department {:id :department :type :string}]]}
   :views {:default {:opts {:widget-namespaces {"c" "stepvine.components"}}
                     :markup chain-markup}}})

(deftest compile-cascades-emits-clearing-events
  (let [events (get-in (cascades/compile-cascades chain-form) [:data :events])
        by-out (into {} (map (juxt (comp first :outputs) :inputs) events))]
    (testing "one clearing event per dependency, parent -> child"
      (is (= [:region] (get by-out :clinic)))
      (is (= [:clinic] (get by-out :department))))))

(deftest domino-clears-the-whole-chain
  ;; Because edits go through domino transact, a clearing event fires only on
  ;; CHANGE (so it never pins the child), and the chain clears transitively in one
  ;; transaction — the engine's DAG does the cascade.
  (let [form (cascades/compile-cascades chain-form)
        s    (-> (impl/create-session form {})
                 (impl/apply-changes [[:region "north"]])
                 (impl/apply-changes [[:clinic "ng"]])
                 (impl/apply-changes [[:department "cardio"]]))]
    (testing "setting fields is NOT pinned by the clearing events"
      (is (= "ng"     (impl/value s :clinic)))
      (is (= "cardio" (impl/value s :department))))
    (testing "changing the top clears every downstream field (one transaction)"
      (let [s2 (impl/apply-changes s [[:region "south"]])]
        (is (= "south" (impl/value s2 :region)))
        (is (= ""      (impl/value s2 :clinic))     "child cleared by its event")
        (is (= ""      (impl/value s2 :department)) "grandchild cleared transitively via the DAG"))))
  (testing "changing a mid-chain field clears only what's below it"
    (let [s (-> (impl/create-session (cascades/compile-cascades chain-form) {})
                (impl/apply-changes [[:region "north"]])
                (impl/apply-changes [[:clinic "ng"]])
                (impl/apply-changes [[:department "cardio"]])
                (impl/apply-changes [[:clinic "other"]]))]
      (is (= "north" (impl/value s :region))     "region (above) untouched")
      (is (= "other" (impl/value s :clinic)))
      (is (= ""      (impl/value s :department)) "department (below) cleared"))))

;; --- change-set-driven re-render (the engine's transaction report) ---------

(deftest change-set-reflects-the-cascade
  (let [form (cascades/compile-cascades chain-form)
        s    (-> (impl/create-session form {})
                 (impl/apply-changes [[:region "north"]])
                 (impl/apply-changes [[:clinic "ng"]])
                 (impl/apply-changes [[:department "cardio"]]))]
    (testing "a single edit reports only that field"
      (is (= #{:department} (impl/changed-ids s))))
    (testing "a cascading edit reports every field the engine moved"
      (is (= #{:region :clinic :department}
             (impl/changed-ids (impl/apply-changes s [[:region "south"]])))))))

(deftest rerender-is-driven-by-what-actually-changed
  (testing "every cascaded field present -> re-render its dependents"
    (is (= [:clinic :department]
           (sort-by name (map :id (render/dropdowns-depending-on
                                   chain-markup aliases #{:region :clinic :department}))))))
  (testing "only the parent moved (child value unchanged) -> re-render just the child"
    (is (= [:clinic]
           (map :id (render/dropdowns-depending-on chain-markup aliases #{:region})))))
  (testing "nothing relevant changed -> nothing re-renders"
    (is (empty? (render/dropdowns-depending-on chain-markup aliases #{:reason})))))
