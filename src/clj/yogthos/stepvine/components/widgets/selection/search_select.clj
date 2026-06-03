(ns yogthos.stepvine.components.widgets.selection.search-select
  "Server-side typeahead (parity stepvine-m8h). Unlike `:typeahead` (an HTML5
   datalist holding the WHOLE option list, filtered in the browser), this widget
   queries a source on the server as you type and morphs in only the MATCHES — so
   the browser never receives the full list. Built for large / remote option sets.

   `:source` names a form source (a `:static` or `:options` kind, which expose a
   query arity). The search box drives an ad-hoc `$<field>_q` signal; picking a
   result sets the field signal `$<field>` and persists it via the normal field
   endpoint — server-authoritative throughout."
  (:require
   [yogthos.stepvine.signals :as signals]
   [yogthos.stepvine.render :refer [render-widget]]))

(defmethod render-widget :stepvine.components/search-select
  [ctx _component {:keys [id label source placeholder]} _body]
  (let [sig        (signals/signal-name id)
        q-name     (str sig "_q")              ; query/display signal NAME (data-bind)
        open       (str "$" sig "_open")       ; results-open flag ($-ref, expressions)
        results-id (str "search-" sig)
        search-url (str "/doc/" (:doc-id ctx) "/search/" (name id)
                        "?source=" (when source (name source)))]
    [:div.widget.search-select.field
     {:id (str "ss-" sig)
      ;; declare the ad-hoc signals this widget uses (the query text + results-open
      ;; flag) so datastar can bind/assign them; the field signal itself is seeded
      ;; by the form
      "data-signals" (str "{" sig "_q: '', " sig "_open: false}")}
     [:label {:for (name id)} (or label (name id))]
     [:div.ss-control
      [:input {:id          (name id)
               :type        "text"
               :autocomplete "off"
               :placeholder (or placeholder "Search…")
               "data-bind"  q-name
               ;; open the results and query the server as you type (debounced)
               "data-on:input__debounce.250ms" (str open "=true; @post('" search-url "')")
               "data-on:focus" (str open "=true")}]
      ;; the stored field value, for confirmation (the source's option value)
      [:span.ss-selected {"data-show" (str "$" sig)} "✓ " [:span {"data-text" (str "$" sig)}]]
      ;; results are morphed in by the server search handler (only matches)
      [:ul {:id results-id :class "ss-results" "data-show" open}]]]))
