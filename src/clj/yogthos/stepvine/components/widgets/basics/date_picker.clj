(ns yogthos.stepvine.components.widgets.basics.date-picker
  "Date input widget (parity stepvine-a3t). A native <input type=date> plus
   configurable constraints resolved server-side:

     :min / :max  a literal \"yyyy-mm-dd\" OR a relative spec — `:today`, or an
                  offset map `{:days N}` / `{:weeks N}` / `{:months N}` (N may be
                  negative), resolved against the server's current date.
     :step        day granularity (e.g. :step 7 for week stepping).
     :helpers     quick-set buttons `[{:label \"Today\" :date <spec>} …]` — each
                  resolves to a literal date and, on click, sets the field and
                  posts it.
     :caption     true to show a friendly reformatted echo of the picked date
                  (the native input always stores/edits ISO yyyy-mm-dd)."
  (:require
   [yogthos.stepvine.components.bind :as bind]
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.endpoints :as endpoints]
   [yogthos.stepvine.render :refer [render-widget]])
  (:import
   (java.time LocalDate)))

(defn- today [ctx]
  (let [t (:today ctx)]
    (cond (instance? LocalDate t) t
          (string? t)             (LocalDate/parse t)
          :else                   (LocalDate/now))))

(defn resolve-date
  "Resolve a date spec to a \"yyyy-mm-dd\" string (or nil). A literal string passes
   through; `:today` and an offset map resolve against the server clock."
  [ctx spec]
  (cond
    (nil? spec)     nil
    (string? spec)  spec
    (= :today spec) (str (today ctx))
    (map? spec)     (str (-> (today ctx)
                             (.plusDays   (long (or (:days spec) 0)))
                             (.plusWeeks  (long (or (:weeks spec) 0)))
                             (.plusMonths (long (or (:months spec) 0)))))
    :else           (str spec)))

(defmethod render-widget :stepvine.components/date-picker
  [ctx _component {:keys [id label min max step placeholder helpers caption read-only]} _body]
  (let [sig      (signals/item-signal-name ctx id)
        in-item? (boolean (:item ctx))
        post-url (endpoints/field-post-url ctx id)]
    [:div.widget.date-picker.field
     [:label {:for (when-not in-item? (name id))}
      (or label (name id))]
     [:input
      (cond-> {:type  "date"
               "data-bind" sig
               :placeholder (or placeholder "yyyy-mm-dd")}
        (not in-item?)             (assoc :id (name id) :name (name id))
        (resolve-date ctx min)     (assoc :min (resolve-date ctx min))
        (resolve-date ctx max)     (assoc :max (resolve-date ctx max))
        step                       (assoc :step (str step))
        read-only                  (assoc :readonly true)
        (not read-only)
        (merge (bind/edit-bind-attrs ctx id sig "data-on:change")))]
     ;; quick-set helpers: set the signal to a resolved literal date, then post
     (when (and (seq helpers) (not read-only))
       (into [:div.date-helpers]
             (for [{:keys [label date]} helpers]
               [:button.date-helper
                {:type "button"
                 "data-on:click" (str "$" sig " = '" (resolve-date ctx date) "'; @post('" post-url "')")}
                label])))
     ;; friendly display echo of the (ISO) value
     (when caption
       [:span.date-caption
        {"data-text" (str "$" sig " ? new Date($" sig "+'T00:00')"
                          ".toLocaleDateString(undefined,{year:'numeric',month:'short',day:'numeric'}) : ''")}])]))
