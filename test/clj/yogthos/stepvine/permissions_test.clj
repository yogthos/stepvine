(ns yogthos.stepvine.permissions-test
  "Granular per-field permissions (parity stepvine-5aa): :write-roles force a field
   read-only (and the server rejects the write); :read-roles hide it. Admins and
   role holders are permitted; everyone else isn't."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.components.widgets.basics.input-field]))  ; register the widget

(def ^:private field-opts
  {:submission {:id :submission}
   :decision   {:id :decision :write-roles #{:reviewer}}
   :score      {:id :score    :read-roles  #{:reviewer}}})

(def ^:private view
  [:div
   [:c/input-field {:id :submission :label "Submission"}]
   [:c/input-field {:id :decision   :label "Decision"}]
   [:c/input-field {:id :score      :label "Score"}]])

(defn- html-for [roles admin?]
  (render/render-view
   {:aliases {"c" "stepvine.components"} :field-opts field-opts :values {}
    :perm-roles (set roles) :perm-admin? admin?}
   view))

(deftest field-permissions-in-render
  (testing "a user without the role: :write-roles field read-only, :read-roles field hidden"
    (let [html (html-for #{} false)]
      (is (str/includes? html "Submission"))
      (is (str/includes? html "Decision"))
      (is (str/includes? html "readonly"))            ; decision forced read-only
      (is (not (str/includes? html "Score")))))       ; score hidden
  (testing "a role holder: writable + visible"
    (let [html (html-for #{:reviewer} false)]
      (is (str/includes? html "Score"))               ; score visible
      (is (not (str/includes? html "readonly")))))    ; decision editable
  (testing "an admin sees and edits everything"
    (let [html (html-for #{} true)]
      (is (str/includes? html "Score"))
      (is (not (str/includes? html "readonly")))))
  (testing "no permission info in ctx -> render normally (re-render path)"
    (let [html (render/render-view
                {:aliases {"c" "stepvine.components"} :field-opts field-opts :values {}}
                view)]
      (is (str/includes? html "Score"))               ; not hidden
      (is (not (str/includes? html "readonly"))))))

(deftest role-permitted
  (let [reviewer {:id "r" :roles #{:reviewer}}
        clerk    {:id "c" :roles #{:clerk}}
        admin    {:id "a" :roles #{:admin}}]
    (is (access/role-permitted? reviewer #{:reviewer}))
    (is (not (access/role-permitted? clerk #{:reviewer})))
    (is (access/role-permitted? admin #{:reviewer}))        ; admin always
    (is (access/role-permitted? clerk #{}))))               ; empty roles = open
