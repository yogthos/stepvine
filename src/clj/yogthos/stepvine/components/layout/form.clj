(ns yogthos.stepvine.components.layout.form
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
        ;; :array fields (multi-select / selection-list / tree-select) seed as an
        ;; empty JSON array so membership tests like ($sig).includes(v) are valid
        ;; before any selection (a "" seed would be a string, not an array).
        array? (into #{} (keep (fn [[id opts]] (when (= :array (:type opts))
                                                 (render/signal-name id)))
                               (:field-opts ctx)))
        ;; lifecycle signals (§15.5): `locked` reflects a finalized/read-only
        ;; document (seeded from the doc status, flipped live on submit/revise);
        ;; `notice` carries transient server messages (failed submit guards).
        seed (into {} (map (fn [[k v]] [k (cond (some? v)  v
                                                (bool? k)  false
                                                (array? k) []
                                                :else      "")]))
                   (merge (render/signal-map ctx)
                          {"uid" uid "presence" 1 "locks" {}
                           "locked" (boolean (:locked? ctx)) "notice" ""}))]
    ;; Rendered as a <div>, not a <form>: there is no submit (inputs POST via
    ;; data-on:input), and an empty data-on:submit value crashes Datastar's
    ;; engine (ValueRequired), which would break every binding on the page.
    ;; data-signals seeds state; data-init opens the SSE stream on load.
    [:div (merge {"data-signals" (json/write-value-as-string seed)
                  "data-init"    (str "@get('/doc/" (:doc-id ctx) "/sse')")}
                 (dissoc attrs :id))
     ;; transient notice + persistent read-only banner
     [:div.sv-notice {"data-show" "$notice" "data-text" "$notice"}]
     [:div.sv-status {"data-show" "$locked"}
      "This document has been submitted and is read-only. Use Revise to reopen it."]
     ;; finalized documents render read-only: a class drives input dimming, and
     ;; the server rejects any write that slips through (§15.5 enforcement)
     (into [:div {"data-class:sv-readonly" "$locked"}]
           (render/render-children ctx body))]))
