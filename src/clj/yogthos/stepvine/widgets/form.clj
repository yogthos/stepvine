(ns yogthos.stepvine.widgets.form
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
        ;; Datastar drops null-valued signals from data-signals, so a field
        ;; seeded null would never become a bindable/sendable signal. Seed nils
        ;; as "" so every field signal exists from the start.
        seed (into {} (map (fn [[k v]] [k (if (nil? v) "" v)]))
                   (merge (render/signal-map ctx) {"uid" uid "presence" 1 "locks" {}}))]
    ;; Rendered as a <div>, not a <form>: there is no submit (inputs POST via
    ;; data-on:input), and an empty data-on:submit value crashes Datastar's
    ;; engine (ValueRequired), which would break every binding on the page.
    ;; data-signals seeds state; data-init opens the SSE stream on load.
    [:div (merge {"data-signals" (json/write-value-as-string seed)
                  "data-init"    (str "@get('/doc/" (:doc-id ctx) "/sse')")}
                 (dissoc attrs :id))
     (render/render-children ctx body)]))
