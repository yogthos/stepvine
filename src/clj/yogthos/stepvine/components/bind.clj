(ns yogthos.stepvine.components.bind
  "Shared Datastar attrs for editable, server-authoritative field widgets.

   Every editable field widget wires the same server-owned locking convention:
   focus acquires the field lock, blur releases it, and the field is `disabled`
   while a *different* user holds the lock (`$locks.<sig>` is that user's uid, or
   empty). The common case also posts the field's new value on its edit event.
   This is the single source of truth for those attrs — widgets differ only in
   their edit event (`data-on:input__debounce.300ms`, `data-on:change`, …) and
   sometimes supply a custom edit handler (then they use `lock-attrs` directly)."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.endpoints :as endpoints]))

(defn lock-attrs
  "The focus→lock / blur→unlock / disabled-while-locked-by-another attrs shared by
   every editable field widget. `sig` is the field's item-signal-name (the bound
   signal); the `disabled` guard reads `$locks.<sig>` against the viewer's `$uid`."
  [ctx id sig]
  {"data-on:focus"      (str "@post('" (endpoints/field-lock-url ctx id) "')")
   "data-on:blur"       (str "@post('" (endpoints/field-unlock-url ctx id) "')")
   "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")})

(defn edit-bind-attrs
  "`lock-attrs` plus the edit POST on `edit-event` — the common case where a field
   change simply posts its new value. `edit-event` is the Datastar event-attr key,
   e.g. \"data-on:input__debounce.300ms\" or \"data-on:change\".

   A debounced text input *also* posts on `change` (which fires when focus leaves
   after an edit), so moving focus or navigating away before the debounce window
   elapses still flushes the value — otherwise the last edit is silently dropped."
  [ctx id sig edit-event]
  (let [post (str "@post('" (endpoints/field-post-url ctx id) "')")]
    (cond-> (assoc (lock-attrs ctx id sig) edit-event post)
      (str/starts-with? (str edit-event) "data-on:input")
      (assoc "data-on:change" post))))
