(ns yogthos.stepvine.components.widgets.overlays.entry-modal
  "Modal data-entry sub-form (parity stepvine-ugx). A modal that hosts scratch
   (temp) fields and, on a trigger, commits them as a NEW row in a collection —
   ibis' `:modal` + `:tmp?` pattern.

     [:c/entry-modal {:coll :lines :signal \"addLine\" :title \"Add line\"
                      :trigger-label \"+ Add line\" :add-label \"Add\"
                      :fields {:item :new-item :qty :new-qty}}  ; item-field -> temp field
      [:c/input-field {:id :new-item :label \"Item\"}]
      [:c/input-field {:id :new-qty  :label \"Qty\"}]]

   The body widgets bind to the temp fields. `Add` posts to
   /doc/:id/coll/:coll/add-from, which builds a row from the temp values, clears
   them, and closes the modal. Open/closed is client-only UI state in `:signal`."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/entry-modal
  [ctx _component {:keys [coll signal title trigger-label add-label fields]
                   :or {signal "entryOpen" add-label "Add"}} body]
  (let [sig   (str "$" signal)
        close (str sig " = false")
        ;; item-field:temp-field pairs the server maps temp signals back from
        fmap  (str/join "," (for [[ifield tfield] fields]
                              (str (name ifield) ":" (name tfield))))
        url   (str "/doc/" (:doc-id ctx) "/coll/" (name coll)
                   "/add-from?modal=" signal "&fields=" fmap)]
    [:div.modal-root
     [:button.modal-trigger {:type "button" "data-on:click" (str sig " = true")}
      (or trigger-label "+ Add")]
     [:div.modal-overlay {"data-show" sig "data-on:click" close}
      [:div.modal-panel {:role "dialog" "aria-modal" "true"
                         "data-on:click" "evt.stopPropagation()"}
       [:div.modal-header
        [:h3.modal-title (str title)]
        [:button.modal-close {:type "button" :aria-label "Close" "data-on:click" close} "×"]]
       (into [:div.modal-body] (render/render-children ctx body))
       [:div.modal-footer
        [:button.modal-cancel {:type "button" "data-on:click" close} "Cancel"]
        [:button.modal-add
         {:type "button" "data-on:click" (str "@post('" url "'); " close)}
         add-label]]]]]))
