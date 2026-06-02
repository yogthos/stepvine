(ns yogthos.stepvine.hydration-test
  "Creation-time field hydration (parity stepvine-xcf): docs/hydrate! stamps fields
   from the creating user (:session), the clock (:today/:now), and literals
   (:value) when a document is created."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.session :as session]))

(def ^:private note-form
  '{:id :note :version 1
    :hydrate {:author  {:session [:display-name]}
              :owner   {:session [:id]}
              :created {:today true}
              :stamp   {:now true}
              :status  {:value "draft"}
              :skip    {:session [:missing]}}    ; resolves to nil -> not set
    :data {:model [[:author {:id :author :type :string}] [:owner {:id :owner :type :string}]
                   [:created {:id :created :type :string}] [:stamp {:id :stamp :type :number}]
                   [:status {:id :status :type :string}] [:skip {:id :skip :type :string}]]}
    :views {:default {:opts {} :markup [:div]}}})

(deftest hydrate-stamps-creation-fields
  (let [docs (atom {})
        mgr  (ig/init-key :session/manager {:documents docs :hub (atom {})})
        doc  (documents/create! docs :note {:created-by "u1" :form-version 1})
        id   (:id doc)
        user {:id "u1" :display-name "Ada Lovelace"}]
    (session/ensure-document! mgr id note-form (:db doc))
    (docs/hydrate! {:session-manager mgr} note-form id user)
    (testing ":session reads the creating user record"
      (is (= "Ada Lovelace" (session/value mgr id :author)))
      (is (= "u1"           (session/value mgr id :owner))))
    (testing ":today / :now use the clock"
      (is (re-matches #"\d{4}-\d{2}-\d{2}" (session/value mgr id :created)))
      (is (number? (session/value mgr id :stamp))))
    (testing ":value is a literal default"
      (is (= "draft" (session/value mgr id :status))))
    (testing "a spec that resolves to nil leaves the field unset"
      (is (nil? (session/value mgr id :skip))))))

(deftest no-hydrate-is-a-noop
  (let [docs (atom {})
        mgr  (ig/init-key :session/manager {:documents docs :hub (atom {})})
        doc  (documents/create! docs :note {:created-by "u1" :form-version 1})
        id   (:id doc)]
    (session/ensure-document! mgr id '{:id :x :version 1 :data {:model [[:a {:id :a}]]}
                                       :views {:default {:opts {} :markup [:div]}}} (:db doc))
    (is (nil? (docs/hydrate! {:session-manager mgr} {:id :x} id {:id "u1"})))))
