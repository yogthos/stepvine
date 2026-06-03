(ns yogthos.stepvine.editor.impl
  (:require [sci.core :as sci]
            [yogthos.stepvine.editor.locks :as locks]
            [yogthos.stepvine.editor.data  :as data]))

(defn eval-form [form-raw]
  (sci/eval-string (pr-str form-raw)
                   {:bindings {'println println
                               'now     #?(:clj  #(java.util.Date.)
                                           :cljs #(js/Date.))
                               'today   #?(:clj  #(.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))
                                           :cljs #(.format ^js (js/Intl.DateTimeFormat. "en-CA" {:dateStyle "short"})))
                               'inst-ms inst-ms}}))


(defn create-session
  "Takes a form and an initial-db, and generates the domino context.
   Other functions of the form specification have yet to be enabled."
  [form-raw initial-db]
  ;; TODO: add async

  ;; TODO: custom bindings
  (let [form (eval-form form-raw)
        ctx (data/initialize-ctx form initial-db)]

    ;; TODO: walk views for direct reactions.
    {:form form
     :form-raw form-raw
     ::data/ctx ctx
     :related-fn (data/get-related-fn ctx)
     :parents-fn (data/get-parents-fn ctx)
     :field-opts-fn (data/get-field-opts-fn ctx) ;; TODO: deprecate in favour of field-opts
     :field-opts (data/get-field-opts-map ctx)
     :connections  #{}
     ::locks/locks {}}))


(defn value [session id]
  (data/get-value (::data/ctx session) id))

(defn db [session]
  (data/get-db (::data/ctx session)))

(defn changed-ids
  "The set of field ids that changed in the session's last transact (domino's
   change report) — drives change-set-based re-rendering and imports."
  [session]
  (data/changed-ids (::data/ctx session)))

(defn emitted-effects
  "Effect intents the engine emitted during the session's last transact — the
   signals the host layer performs (email, pdf, import, …)."
  [session]
  (data/emitted-effects session))

(defn apply-changes [session changes]
  (data/transact-ctx session changes))
