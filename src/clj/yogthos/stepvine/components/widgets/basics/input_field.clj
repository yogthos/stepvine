(ns yogthos.stepvine.components.widgets.basics.input-field
  "Text/number input field — two-way bound, server-authoritative locking."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/input-field
  [ctx _component {:keys [id label read-only error]} _body]
  (let [opts     (get-in ctx [:field-opts id])
        nm       (name id)
        in-item? (boolean (:item ctx))
        sig      (render/item-signal-name ctx id)   ; <field> or <coll>_<idx>_<field>
        value    (get-in ctx [:values id])
        number?  (= :number (:type opts))
        err-sig  (when error (render/$ error))]
    [:div.field
     [:label label]
     [:input
      ;; two-way bind via the value form `data-bind="<signal>"` (bare signal
      ;; name; references elsewhere use $name). Datastar takes control of the
      ;; input once its async module loads + the data-init SSE opens, re-applying
      ;; the data-signals seed — so the seed must already carry this field.
      (cond-> {:type  (if number? "number" "text")
               :value (if (nil? value) "" (str value))}
        true              (assoc "data-bind" sig)
        ;; only top-level fields get a stable id/name (item ids would collide)
        (not in-item?)    (assoc :id nm :name nm)
        (:required? opts) (assoc :required true)
        read-only         (assoc :readonly true)
        err-sig           (assoc "data-attr:aria-invalid" (str "!!" err-sig))
        (not read-only)
        ;; edit + server-authoritative locking, uniform for top-level and items
        ;; (item urls/signals are coll-scoped via field-*-url and item-signal-name)
        (assoc "data-on:input__debounce.300ms" (str "@post('" (render/field-post-url ctx id) "')")
               "data-on:focus" (str "@post('" (render/field-lock-url ctx id) "')")
               "data-on:blur"  (str "@post('" (render/field-unlock-url ctx id) "')")
               ;; clean boolean: when unlocked $locks.<sig> is undefined, and a
               ;; bare `&&` would yield undefined (which datastar treats as set);
               ;; `!!` forces false so the disabled attribute is removed.
               "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")))]
     (when err-sig
       [:span.error {"data-text" err-sig "data-show" err-sig}])]))
