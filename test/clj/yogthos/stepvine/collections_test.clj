(ns yogthos.stepvine.collections-test
  "Phase 4b: collections (Domino subcontexts). Add/edit/remove items with
   per-item derived fields recomputing, item-scoped signals, and broadcast."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]
   [starfederation.datastar.clojure.adapter.test :as ds-test]
   yogthos.stepvine.widgets   ; register widget render methods
   [yogthos.stepvine.editor.impl :as impl]))

(defn- mgr+ []
  (let [docs (atom {}) h (atom {})]
    [docs h (ig/init-key :session/manager {:documents docs :hub h})]))

(deftest add-edit-remove-items-with-per-item-derived-field
  (let [[_ _ mgr] (mgr+)
        form (forms/load-form "roster")]
    (session/ensure-document! mgr "roster" form {})
    (let [i1 (session/add-item! mgr "roster" :members)
          i2 (session/add-item! mgr "roster" :members)]
      (testing "two items were created"
        (is (= 2 (count (get-in (render/collections-data (session/current mgr "roster"))
                                [:members :order])))))
      (testing "a per-item edit recomputes that item's derived field"
        (session/set-item-field! mgr "roster" :members i1 :first "Ada")
        (session/set-item-field! mgr "roster" :members i1 :last "Lovelace")
        (is (= "Ada Lovelace"
               (impl/value (session/current mgr "roster") [:members i1 :full]))))
      (testing "items are independent (i2 untouched — its event hasn't fired)"
        (is (nil? (impl/value (session/current mgr "roster") [:members i2 :full]))))
      (testing "item signals are namespaced <coll>_<idx>_<field>"
        (let [sm (render/session->signal-map (session/current mgr "roster"))]
          (is (= "Ada"          (get sm (str "members_" i1 "_first"))))
          (is (= "Ada Lovelace" (get sm (str "members_" i1 "_full"))))))
      (testing "removing an item drops it"
        (session/remove-item! mgr "roster" :members i2)
        (is (= [i1] (get-in (render/collections-data (session/current mgr "roster"))
                            [:members :order])))))))

(deftest collection-change-broadcasts-to-peers
  (let [[_ h mgr] (mgr+)
        form (forms/load-form "roster")
        peer (ds-test/->sse-recorder)]
    (session/ensure-document! mgr "roster" form {})
    (hub/register! h "roster" "a" peer "u")
    (let [idx (session/add-item! mgr "roster" :members)]
      (session/set-item-field! mgr "roster" :members idx :first "Grace")
      (let [e (last @(:!rec peer))]
        (is (str/includes? e "datastar-patch-signals"))
        (is (str/includes? e (str "members_" idx "_first")))
        (is (str/includes? e "Grace"))))))

(deftest per-item-reaction-recomputes-and-broadcasts
  (let [[_ _ mgr] (mgr+)
        form (forms/load-form "roster")]
    (session/ensure-document! mgr "roster" form {})
    (let [idx (session/add-item! mgr "roster" :members)]
      (session/set-item-field! mgr "roster" :members idx :first "grace")
      (session/set-item-field! mgr "roster" :members idx :last "hopper")
      (testing "the per-item reaction recomputes (read via vector id)"
        (is (= "gh" (impl/value (session/current mgr "roster") [:members idx :initials]))))
      (testing "and is carried in the item-scoped signal map"
        (let [sm (render/session->signal-map (session/current mgr "roster"))]
          (is (= "gh" (get sm (str "members_" idx "_initials")))))))))

(deftest per-item-locking-is-independent-and-enforced
  (let [[_ h mgr] (mgr+)
        form (forms/load-form "roster")
        peer (ds-test/->sse-recorder)]
    (session/ensure-document! mgr "roster" form {})
    (hub/register! h "roster" "a" peer "obs")
    (let [i1 (session/add-item! mgr "roster" :members)
          i2 (session/add-item! mgr "roster" :members)]
      (testing "locking an item field broadcasts an item-scoped lock signal"
        (is (= :ok (session/lock-item-field! mgr h "roster" "bob" :members i1 :first)))
        (let [e (last @(:!rec peer))]
          (is (str/includes? e (str "members_" i1 "_first")))
          (is (str/includes? e "bob"))))
      (testing "a different user can lock a different item concurrently"
        (is (= :ok (session/lock-item-field! mgr h "roster" "carol" :members i2 :first))))
      (testing "a non-owner cannot save the locked item field"
        (is (= :rejected (session/apply-item-field-as! mgr "roster" "carol" :members i1 :first "X")))
        (is (nil? (impl/value (session/current mgr "roster") [:members i1 :first]))))
      (testing "the lock owner can save it"
        (is (= :ok (session/apply-item-field-as! mgr "roster" "bob" :members i1 :first "Grace")))
        (is (= "Grace" (impl/value (session/current mgr "roster") [:members i1 :first]))))
      (testing "unlock clears the item-scoped lock signal"
        (session/unlock-item-field! mgr h "roster" "bob" :members i1 :first)
        (is (str/includes? (last @(:!rec peer)) (str "\"members_" i1 "_first\":null")))))))

(deftest collection-item-dropdown-is-item-scoped-with-options
  ;; the builder's :fields collection has a ftype dropdown sourcing
  ;; :field-types/all — a collection-item dropdown must bind the item-scoped
  ;; signal, post to the item endpoint, and render its resolved options.
  (let [[_ _ mgr] (mgr+)
        form  (forms/load-form "builder")
        store {:field-types/all [{:value "number" :label "Number"}
                                 {:value "string" :label "Text"}]}]
    (session/ensure-document! mgr "builder" form {})
    (let [idx  (session/add-item! mgr "builder" :fields)
          sess (session/current mgr "builder")
          ctx  (-> (render/session->context sess :default "builder")
                   (assoc :options (options/resolve-field-options
                                    store (render/all-field-opts sess))))
          html (render/render-collection ctx sess :default :fields)]
      (testing "binds the item-scoped signal, not the bare field signal"
        (is (str/includes? html (str "data-bind=\"fields_" idx "_ftype\"")))
        (is (not (str/includes? html "data-bind=\"ftype\""))))
      (testing "posts to the collection-item field endpoint"
        (is (str/includes? html (str "/coll/fields/" idx "/field/ftype"))))
      (testing "renders its resolved options"
        (is (str/includes? html "value=\"number\""))
        (is (str/includes? html ">Number</option>"))))))

(deftest renders-collection-container-html
  (let [[_ _ mgr] (mgr+)
        form (forms/load-form "roster")]
    (session/ensure-document! mgr "roster" form {})
    (let [idx  (session/add-item! mgr "roster" :members)
          sess (session/current mgr "roster")
          ctx  (render/session->context sess :default "roster")
          html (render/render-collection ctx sess :default :members)]
      (is (str/includes? html "id=\"coll-members\""))
      (is (str/includes? html (str "id=\"item-members-" idx "\"")))
      (is (str/includes? html (str "data-bind=\"members_" idx "_first\"")))
      (is (str/includes? html "/coll/members/add"))
      (is (str/includes? html (str "/coll/members/" idx "/remove"))))))

;; --- Table view-state (sort / page / row order) ---------------------------

(defn- order-of [mgr]
  (get-in (render/collections-data (session/current mgr "roster")) [:members :order]))

(deftest move-item-reorders-by-view-state
  (let [[_ _ mgr] (mgr+)
        form (forms/load-form "roster")]
    (session/ensure-document! mgr "roster" form {})
    (let [i1 (session/add-item! mgr "roster" :members)
          i2 (session/add-item! mgr "roster" :members)
          i3 (session/add-item! mgr "roster" :members)]
      (testing "initial order is insertion order"
        (is (= [i1 i2 i3] (order-of mgr))))
      (testing "moving i3 before i1 reorders the collection"
        (session/move-item! mgr "roster" :members i3 i1)
        (is (= [i3 i1 i2] (order-of mgr))))
      (testing "a newly added item appears after the stored order"
        (let [i4 (session/add-item! mgr "roster" :members)]
          (is (= [i3 i1 i2 i4] (order-of mgr)))))
      (testing "removing an item drops it from the rendered order"
        (session/remove-item! mgr "roster" :members i1)
        (is (= [i3 i2] (vec (filter #{i3 i2} (order-of mgr)))))
        (is (not (some #{i1} (order-of mgr))))))))

(defn- render-tasks [mgr]
  (let [sess (session/current mgr "tasks")
        ctx  (assoc (render/session->context sess :default "tasks") :options {})]
    (render/render-view ctx (render/view-markup sess :default))))

(defn- positions [html & labels]
  (map #(str/index-of html (str "value=\"" % "\"")) labels))

(defn- row-count [html] (count (re-seq #"id=\"row-tasks-" html)))

(deftest table-sort-and-page
  (let [[_ _ mgr] (mgr+)
        form (forms/load-form "tasks")]   ; page-size 3, columns Title + Priority
    (session/ensure-document! mgr "tasks" form {})
    (let [a (session/add-item! mgr "tasks" :tasks)
          b (session/add-item! mgr "tasks" :tasks)
          c (session/add-item! mgr "tasks" :tasks)]
      (doseq [[idx t p] [[a "Charlie" 3] [b "Alice" 1] [c "Bob" 2]]]
        (session/set-item-field! mgr "tasks" :tasks idx :title t)
        (session/set-item-field! mgr "tasks" :tasks idx :priority p))
      (testing "no sort: 3 rows on a single page"
        (let [html (render-tasks mgr)]
          (is (= 3 (row-count html)))
          (is (str/includes? html "Page 1 of 1"))))
      (testing "sort by title ascending orders the rows alphabetically"
        (session/set-table-sort! mgr "tasks" :tasks "title")
        (let [[alice bob charlie] (positions (render-tasks mgr) "Alice" "Bob" "Charlie")]
          (is (< alice bob charlie))))
      (testing "clicking the same column again toggles to descending"
        (session/set-table-sort! mgr "tasks" :tasks "title")
        (let [[charlie bob alice] (positions (render-tasks mgr) "Charlie" "Bob" "Alice")]
          (is (< charlie bob alice))))
      (testing "sort by priority is numeric (1,2,3 -> Alice,Bob,Charlie)"
        (session/set-table-sort! mgr "tasks" :tasks "title")      ; third cycle clears
        (session/set-table-sort! mgr "tasks" :tasks "priority")
        (let [[alice bob charlie] (positions (render-tasks mgr) "Alice" "Bob" "Charlie")]
          (is (< alice bob charlie))))
      (testing "paging splits a 4th row onto page 2"
        (let [d (session/add-item! mgr "tasks" :tasks)]
          (session/set-item-field! mgr "tasks" :tasks d :title "Dave")
          (is (str/includes? (render-tasks mgr) "Page 1 of 2"))
          (is (= 3 (row-count (render-tasks mgr))))
          (session/set-table-page! mgr "tasks" :tasks "next")
          (is (str/includes? (render-tasks mgr) "Page 2 of 2"))
          (is (= 1 (row-count (render-tasks mgr)))))))))
