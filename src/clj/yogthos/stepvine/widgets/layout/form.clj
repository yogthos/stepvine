(ns yogthos.stepvine.widgets.layout.form
  "Form container widget — seeds all signals and opens the SSE stream."
  (:require
   [jsonista.core :as json]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/form
  [ctx _component attrs body]
  ;; The form seeds all signals once via data-signals (field/reaction/collection
  ;; values plus system signals: this client's uid, presence count and per-field
  ;; lock map), opens the SSE stream on load — tagged with uid so the server can
  ;; release this client's locks on disconnect — and suppresses native submit.
  (let [uid  (:uid ctx)
        ;; :boolean fields seed as a JSON boolean (so e.g. checkboxes bind a real
        ;; true/false, not the string "on"); everything else seeds nil -> ""
        ;; (Datastar drops null signals, so an unseeded field would never become
        ;; a bindable/sendable signal).
        bool? (into #{} (keep (fn [[id opts]] (when (= :boolean (:type opts))
                                                (render/signal-name id)))
                              (:field-opts ctx)))
        seed (into {} (map (fn [[k v]] [k (cond (some? v)  v
                                                (bool? k)  false
                                                :else      "")]))
                   (merge (render/signal-map ctx) {"uid" uid "presence" 1 "locks" {}}))]
    ;; Rendered as a <div>, not a <form>: there is no submit (inputs POST via
    ;; data-on:input), and an empty data-on:submit value crashes Datastar's
    ;; engine (ValueRequired), which would break every binding on the page.
    ;; data-signals seeds state; data-init opens the SSE stream on load.
    [:div (merge {"data-signals" (json/write-value-as-string seed)
                  "data-init"    (str "@get('/doc/" (:doc-id ctx) "/sse')")}
                 (dissoc attrs :id))
     (render/render-children ctx body)]))
