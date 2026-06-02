(ns yogthos.stepvine.builder-test
  "P3: the visual form builder generates and persists real forms."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [yogthos.stepvine.builder :as builder]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.session :as session]))

(deftest build-form-from-a-builder-document
  (let [form (forms/load-form "builder")
        mgr  (ig/init-key :session/manager {:documents (atom {}) :hub nil})]
    (session/ensure-document! mgr "b" form {})
    (session/apply-change! mgr "b" [[:form-id "contact"] [:form-title "Contact form"]])
    (let [i1 (session/add-item! mgr "b" :fields)
          i2 (session/add-item! mgr "b" :fields)]
      (session/set-item-field! mgr "b" :fields i1 :fid "name")
      (session/set-item-field! mgr "b" :fields i1 :flabel "Name")
      (session/set-item-field! mgr "b" :fields i1 :ftype "string")
      (session/set-item-field! mgr "b" :fields i2 :fid "age")
      (session/set-item-field! mgr "b" :fields i2 :flabel "Age")
      (session/set-item-field! mgr "b" :fields i2 :ftype "number")
      (let [gen (builder/build-form (session/current mgr "b"))]
        (testing "form metadata"
          (is (= :contact (:id gen)))
          (is (= "Contact form" (:title gen)))
          (is (= 1 (:version gen))))
        (testing "model carries each field with its type"
          (is (= #{[:name {:id :name :type :string}]
                   [:age  {:id :age  :type :number}]}
                 (set (get-in gen [:data :model])))))
        (testing "default view has a labelled input per field"
          (let [markup (get-in gen [:views :default :markup])]
            (is (some #{[:c/input-field {:label "Name" :id :name}]} markup))
            (is (some #{[:c/input-field {:label "Age"  :id :age}]}  markup))))))))

(deftest blank-form-id-builds-nothing
  (let [form (forms/load-form "builder")
        mgr  (ig/init-key :session/manager {:documents (atom {}) :hub nil})]
    (session/ensure-document! mgr "b" form {})
    (is (nil? (builder/build-form (session/current mgr "b"))))))

(deftest save-form-persists-and-reloads
  (let [dir   (str (System/getProperty "java.io.tmpdir") "/sv-forms-" (System/nanoTime))
        _     (.mkdirs (io/file dir))
        store (forms/atom-store {:dir dir})
        form  {:id :gen :title "Gen" :version 1
               :data {:model [[:x {:id :x :type :string}]]} :views {}}]
    (forms/save-form! store form)
    (testing "available in-memory and on disk, and reloadable"
      (is (= :gen (:id (forms/get-form store "gen"))))
      (is (.exists (io/file dir "gen.edn")))
      (is (= form (forms/load-form dir "gen"))))
    (io/delete-file (io/file dir "gen.edn") true)
    (io/delete-file (io/file dir) true)))
