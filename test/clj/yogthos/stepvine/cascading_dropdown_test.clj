(ns yogthos.stepvine.cascading-dropdown-test
  "Parity (stepvine-9wo): cascading / dependent dropdowns. A dropdown that
   :depends-on a parent field shows only the options whose :when matches the
   parent's current value; the server finds such dropdowns to re-render them when
   the parent changes."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
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
