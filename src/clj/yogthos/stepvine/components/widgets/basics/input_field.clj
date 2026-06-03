(ns yogthos.stepvine.components.widgets.basics.input-field
  "Text/number input field — two-way bound, server-authoritative locking."
  (:require
   [yogthos.stepvine.components.bind :as bind]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/input-field
  [ctx _component {:keys [id label read-only error]} _body]
  (let [opts     (get-in ctx [:field-opts id])
        nm       (name id)
        in-item? (boolean (:item ctx))
        sig      (signals/item-signal-name ctx id)   ; <field> or <coll>_<idx>_<field>
        value    (get-in ctx [:values id])
        number?  (= :number (:type opts))
        err-sig  (when error (signals/$ error))]
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
        ;; (item urls/signals are coll-scoped via field-*-url and item-signal-name).
        ;; The disabled guard `!!$locks.<sig> && …` forces a clean boolean so an
        ;; unset lock (undefined) removes the attr — see components.bind.
        (merge (bind/edit-bind-attrs ctx id sig "data-on:input__debounce.300ms")))]
     (when err-sig
       [:span.error {"data-text" err-sig "data-show" err-sig}])]))
