(ns yogthos.stepvine.workflow-email-test
  "Parity (stepvine-bcg): the :email workflow step. Running an action whose steps
   include {:do :email …} sends a templated message through the resources' mailer.
   Uses the recording mailer so the outbox is inspectable."
  (:require
   [clojure.test :refer [deftest testing is]]
   [integrant.core :as ig]
   [mycelium.core :as myc]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.mailer :as mailer]
   [yogthos.stepvine.session :as session]
   [yogthos.stepvine.versions :as versions]
   [yogthos.stepvine.workflows.workflow :as wf-fsm]))

(def ticket-form
  '{:id :ticket :version 1
    :data {:model [[:title {:id :title :type :string :validation [:required]}]]}
    :workflow {:initial :open
               :states  {:open   {:on {:submit :review}}
                         :review {:terminal? true}}
               :actions {:submit {:guard :valid?
                                  :steps [{:do :notify :message "Submitted for review"}
                                          {:do :email
                                           :to      "ops@example.com"
                                           :subject ["New ticket: " {:from [:title]}]
                                           :body    ["A ticket was submitted: " {:from [:title]}]}]}}}})

(defn- run [res id action uid]
  (myc/run-compiled wf-fsm/run-action res
                    {:http-request {:path-params {:id id :action (name action)}
                                    :session {:user-id uid}}}))

(deftest email-step-sends-templated-message
  (let [archive (versions/archive)
        _       (versions/publish! archive ticket-form)
        forms   (forms/atom-store {:dir nil :forms {:ticket ticket-form} :versions archive})
        docs    (atom {})
        h       (atom {})
        mgr     (ig/init-key :session/manager {:documents docs :hub h})
        mbox    (mailer/recording)
        doc     (documents/create! docs :ticket {:created-by "u1" :form-version 1})
        id      (:id doc)
        res     {:forms forms :documents docs :session-manager mgr :hub h
                 :audit (audit/store) :mailer mbox
                 :users (atom {"u1" {:id "u1" :roles #{:reviewer}}})}]
    (docs/ensure! res id)
    (session/apply-change! mgr id [[:title "Printer is down"]])     ; make :valid? true
    (testing "the action commits"
      (is (= 204 (:status (run res id :submit "u1")))))
    (testing "one email was sent, with subject/body templated from the document"
      (let [box (mailer/outbox mbox)]
        (is (= 1 (count box)))
        (let [{:keys [to subject body]} (first box)]
          (is (= "ops@example.com" to))
          (is (= "New ticket: Printer is down" subject))
          (is (= "A ticket was submitted: Printer is down" body)))))))

(deftest no-mailer-is-a-safe-noop
  ;; an :email directive with no mailer in resources must not crash the action
  (is (nil? (mailer/send! nil {:to "x" :subject "y" :body "z"}))))
