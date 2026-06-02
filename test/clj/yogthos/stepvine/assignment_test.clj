(ns yogthos.stepvine.assignment-test
  "Parity (stepvine-7sb): document assignment + routing. assign! records the
   assignee; a workflow :assign step routes the document; the assignee may open it."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [mycelium.core :as myc]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.mailer :as mailer]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.versions :as versions]
   [yogthos.stepvine.web.security :as security]
   [yogthos.stepvine.workflows.workflow :as wf-fsm]))

(deftest assign-and-clear
  (let [store (atom {})
        d     (documents/create! store :ticket {:created-by "owner"})
        id    (:id d)]
    (is (nil? (documents/assignee (documents/get-document store id))))
    (documents/assign! store id "rev1" "owner")
    (is (= "rev1" (documents/assignee (documents/get-document store id))))
    (testing "history is appended; assigning nil clears"
      (documents/assign! store id nil "owner")
      (is (nil? (documents/assignee (documents/get-document store id))))
      (is (= 2 (count (get-in (documents/get-document store id) [:meta :assignments])))))))

(deftest assignee-can-open-the-document
  (let [store (atom {})
        d     (documents/create! store :ticket {:created-by "owner"})
        ok    (constantly {:status 200})
        h     ((security/wrap-doc-access store) ok)
        req   (fn [uid] {:path-params {:id (:id d)} :session {:user-id uid}})]
    (is (= 403 (:status (h (req "rev1")))) "before assignment, a non-owner is blocked")
    (documents/assign! store (:id d) "rev1" "owner")
    (is (= 200 (:status (h (req "rev1")))) "the assignee may open it")
    (is (= 200 (:status (h (req "owner")))) "the owner still may")))

;; --- the :assign workflow step routes the document ------------------------

(def routing-form
  '{:id :ticket :version 1
    :data {:model [[:title {:id :title :type :string :validation [:required]}]]}
    :workflow {:initial :open
               :states  {:open {:on {:submit :review}} :review {:terminal? true}}
               :actions {:submit {:guard :valid?
                                  :steps [{:do :assign :to "reviewer-1"}]}}}})

(deftest assign-step-routes-on-transition
  (let [archive (versions/archive)
        _       (versions/publish! archive routing-form)
        forms   (forms/atom-store {:dir nil :forms {:ticket routing-form} :versions archive})
        docs    (atom {})
        h       (atom {})
        mgr     (ig/init-key :session/manager {:documents docs :hub h})
        doc     (documents/create! docs :ticket {:created-by "u1" :form-version 1})
        id      (:id doc)
        res     {:forms forms :documents docs :session-manager mgr :hub h
                 :audit (audit/store) :mailer (mailer/recording)
                 :users (atom {"u1" {:id "u1" :roles #{}}})}]
    (docs/ensure! res id)
    (session/apply-change! mgr id [[:title "Printer down"]])
    (let [out (myc/run-compiled wf-fsm/run-action res
                                {:http-request {:path-params {:id id :action "submit"}
                                                :session {:user-id "u1"}}})]
      (is (= 204 (:status out)))
      (testing "the submit action routed the document to reviewer-1"
        (is (= "reviewer-1" (documents/assignee (documents/get-document docs id))))))))
