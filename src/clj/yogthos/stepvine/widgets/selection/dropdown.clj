(ns yogthos.stepvine.widgets.selection.dropdown
  "Select/dropdown widget with option formatting, placeholder, and filtering.
   Serves both `:dropdown` and the form-facing `:dropdown-select` alias."
  (:require
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defn- option-value [option]
  (cond
    (map? option)    (:value option)
    (vector? option) (second option)
    :else            option))

(defn- option-label [option]
  (cond
    (map? option)    (:label option)
    (vector? option) (first option)
    :else            option))

(defn- format-option [option fmt]
  (if (and fmt (map? option))
    (let [fmt-str (first fmt)
          fmt-args (rest fmt)]
      (apply format fmt-str (map #(get option %) fmt-args)))
    option))

(defn render-dropdown
  "Render a labelled <select> bound to a (possibly item-scoped) signal, with
   options from the `:options` attr or the resolved option sources in the ctx."
  [ctx {:keys [id label options placeholder fmt read-only]}]
  (let [sig      (render/item-signal-name ctx id)
        in-item? (boolean (:item ctx))
        current  (get-in ctx [:values id])
        opts     (or options (get-in ctx [:options id]) [])]
    [:div.widget.dropdown.field
     [:label {:for (when-not in-item? (name id))}
      (or label (name id))]
     (into [:select
            (cond-> {"data-bind" sig
                     "data-on:change" (str "@post('" (render/field-post-url ctx id) "')")
                     "data-on:focus"  (str "@post('" (render/field-lock-url ctx id) "')")
                     "data-on:blur"   (str "@post('" (render/field-unlock-url ctx id) "')")
                     "data-attr:disabled" (str "!!$locks." sig " && $locks." sig " != $uid")}
              ;; only top-level fields get a stable id/name (item ids would collide)
              (not in-item?) (assoc :id (name id) :name (name id))
              read-only      (assoc :disabled true))]
           (cons [:option {:value ""} (or placeholder "— select —")]
                 (for [o opts]
                   (let [v (option-value o)
                         l (option-label o)
                         disp (format-option l fmt)]
                     [:option
                      (cond-> {:value (str v)}
                        (= v current) (assoc :selected true))
                      (str disp)]))))]))

(defmethod render-widget :stepvine.components/dropdown
  [ctx _component attrs _body]
  (render-dropdown ctx attrs))

;; Form-facing alias used by `:c/dropdown-select` in form definitions.
(defmethod render-widget :stepvine.components/dropdown-select
  [ctx _component attrs _body]
  (render-dropdown ctx attrs))
