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
