(ns yogthos.stepvine.components.widgets.tables.table
  "Server-rendered table widget with per-cell locking, custom cell widgets,
   sorting, filtering, paging, drag-and-drop rows/columns, column customization,
   and horizontal/vertical modes.

   Design mirrors ca.uhn.widgets.components.table:
   - Each cell is an independent widget with lock-container-path scoped by
     lock-granularity (:cell, :row, or :table).
   - Sorting, filtering, and paging are server-side via @post.
   - Row drag-and-drop uses inline JS HTML5 DnD + fetch POST to
     /coll/<id>/move-row.
   - Column reorder/remove uses inline JS HTML5 DnD + fetch POST to
     /coll/<id>/columns-move and /coll/<id>/columns-remove.
   - Column customization: editable labels, add column via POST to
     /coll/<id>/columns-add."
  (:require
   [clojure.string :as str]
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

;; ═══════════════════════════════════════════════════════════════════════════
;; helpers
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private asc-arrow  " ▲")
(def ^:private desc-arrow " ▼")

(defn- ->path-vector [x] (if (coll? x) (vec x) [x]))

(defn apply-column-overlay
  "Apply a view-state column overlay (`{:order :hidden :labels}`) to the declared
   `columns`: reorder by `:order` (declared columns missing from it keep their
   place at the end), drop `:hidden` paths, and override `:label`s. With no
   overlay the declared columns pass through unchanged."
  [declared {:keys [order hidden labels]}]
  (let [by-path  (into {} (map (juxt :path identity)) declared)
        ordered  (if (seq order)
                   (concat (keep by-path order)
                           (remove #(some #{(:path %)} order) declared))
                   declared)
        hidden?  (set hidden)]
    (mapv (fn [c] (cond-> c (contains? labels (:path c)) (assoc :label (get labels (:path c)))))
          (remove #(hidden? (:path %)) ordered))))

(defn- coll-base [ctx coll-id]
  (str "/doc/" (:doc-id ctx) "/coll/" (name coll-id)))

(defn- cmp-vals [a b]
  (cond
    (= a b)                        0
    (nil? a)                       1
    (nil? b)                       -1
    (and (number? a) (number? b))  (compare a b)
    :else                          (compare (str a) (str b))))

(defn- apply-view
  "Apply the table's server-side view-state (filter, sort column/dir, paging) to
   the row order. Returns {:order display-order :sort {:col :dir} :page p :pages n
   :filter {:col :value} :total n}."
  [ctx coll-id order items {:keys [page-size paged?]}]
  (let [{:keys [sort page] flt :filter :or {page 0}} (get-in ctx [:view-state coll-id])
        ;; row filter: keep rows whose filter column matches the chosen value
        filtered (if-let [{:keys [col value]} flt]
                   (vec (clojure.core/filter
                         (fn [idx] (= (str value) (str (get-in items [idx col])))) order))
                   (vec order))
        sorted (if-let [col (:col sort)]
                 (let [asc (sort-by #(get-in items [% col]) cmp-vals filtered)]
                   (vec (if (= :desc (:dir sort)) (reverse asc) asc)))
                 filtered)
        total  (count sorted)
        psize  (max 1 (or page-size 100))
        pages  (max 1 (long (Math/ceil (/ (double (max 1 total)) psize))))
        page   (max 0 (min page (dec pages)))
        win    (if paged?
                 (subvec sorted (min (* page psize) total) (min (* (inc page) psize) total))
                 sorted)]
    {:order win :sort sort :page page :pages pages :total total :filter flt}))

;; ═══════════════════════════════════════════════════════════════════════════
;; per-cell widget construction
;; ═══════════════════════════════════════════════════════════════════════════

(defn build-cell-node
  [{:keys [lock-granularity] :or {lock-granularity :cell}}
   table-path
   {:keys [path read-only? widget] :as column}
   row-path
   row-read-only?]
  (let [cell-path  (into row-path (->path-vector path))
        defaults   {:id                  path
                    :read-only?          (boolean (or read-only? row-read-only?))
                    :label               (:label column)
                    :parent              table-path
                    :path                cell-path
                    :lock-container-path (case lock-granularity
                                           :table table-path
                                           :row   row-path
                                           :cell  cell-path
                                           row-path)}
        [component-kw extra-attrs]
        (cond (keyword? widget)    [widget {}]
              (and (vector? widget) (keyword? (first widget)))
              [(first widget) (if (map? (second widget)) (second widget) {})]
              :else [:stepvine.components/labeled-value {}])]
    [component-kw (merge defaults extra-attrs)]))

;; ═══════════════════════════════════════════════════════════════════════════
;; sort header
;; ═══════════════════════════════════════════════════════════════════════════

(defn- sort-indicator [dir] (case dir :asc asc-arrow :desc desc-arrow ""))

(defn- sort-header-cell
  [ctx coll-id {:keys [path label]} current-sort-col current-sort-dir]
  (let [col-name  (name path)
        csc       (when current-sort-col (name current-sort-col))
        sort-expr (str "@post('" (coll-base ctx coll-id) "/sort?col=" col-name "')")]
    [:th.widget-table-sortable-column
     {"data-on:click" sort-expr}
     (str (or label col-name))
     [:span.widget-table-sort-direction
      (sort-indicator (when (= col-name csc) current-sort-dir))]]))

;; ═══════════════════════════════════════════════════════════════════════════
;; editable label (for customizable columns)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- editable-label
  [ctx coll-id {:keys [path label default-label]}]
  (let [sig (render/item-signal-name ctx (keyword (str "col-label-" (name path))))]
    [:input.widget-table-editable-label
     {:type        "text"
      :value       (str (or label ""))
      :placeholder (str (or default-label (name path)))
      "data-bind"  sig
      "data-on:input__debounce.500ms"
      (str "@post('" (coll-base ctx coll-id) "/columns-label?col=" (name path)
           "&label='+encodeURIComponent($" sig "))")}]))

;; ═══════════════════════════════════════════════════════════════════════════
;; filter UI
;; ═══════════════════════════════════════════════════════════════════════════

(defn- filter-dropdown
  "A column filter: choose a value present in `:col` to narrow the rows. Options
   are an explicit `:source` (from the options store) or, by default, the distinct
   values present in that column. The choice posts to /filter and the server
   re-renders with the filtered view; the active value persists in view-state."
  [ctx coll-id {:keys [col label source] :or {label "Filter:"}}]
  (let [col       (keyword col)
        sig       (render/item-signal-name ctx (keyword (str (name coll-id) "-filter")))
        items     (get-in ctx [:collections coll-id :items])
        explicit  (when source (get-in ctx [:options source]))
        values    (if explicit
                    (map (fn [o] (if (vector? o) (second o) o)) explicit)
                    (->> (vals items) (map #(get % col)) (remove nil?)
                         (map str) distinct sort))
        current   (get-in ctx [:view-state coll-id :filter :value])]
    [:div.widget-table-filter
     [:label label]
     [:select {"data-bind" sig
               "data-on:change"
               (str "@post('" (coll-base ctx coll-id) "/filter?col=" (name col)
                    "&value='+encodeURIComponent($" sig "))")}
      [:option {:value ""} "All"]
      (for [v values]
        [:option (cond-> {:value (str v)}
                   (= (str v) (str current)) (assoc :selected true))
         (str v)])]]))

;; ═══════════════════════════════════════════════════════════════════════════
;; paging
;; ═══════════════════════════════════════════════════════════════════════════

(defn- page-controls
  "Server-state pager: prev/next POST /page?dir=… and the server re-renders with
   the new page; the page indicator is rendered server-side."
  [ctx coll-id page pages]
  (let [base (coll-base ctx coll-id)]
    [:div.widget-table-pager
     [:nav [:ul.pagination
            [:li.page-item {:class (when (<= page 0) "disabled")}
             [:a.page-link {:href "#" "data-on:click" (str "@post('" base "/page?dir=prev')")} "«"]]
            [:li.page-item.disabled
             [:span.page-link (str "Page " (inc page) " of " pages)]]
            [:li.page-item {:class (when (>= (inc page) pages) "disabled")}
             [:a.page-link {:href "#" "data-on:click" (str "@post('" base "/page?dir=next')")} "»"]]]]]))

;; ═══════════════════════════════════════════════════════════════════════════
;; inline JS for drag-and-drop + horizontal scroll
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private row-dnd-js
  "HTML5 row drag-and-drop via the row grip handle. Listeners are delegated on
   `document` and installed once (the table element is replaced on every
   re-render, so table-bound listeners would be lost). The drop POSTs move-row
   with the Datastar-Request header (the route requires it); the reorder is
   applied via the server's SSE patch-elements broadcast."
  "(function(){
     if(window.__svRowDnd)return; window.__svRowDnd=true;
     var T='table-row';
     function rowFor(el){return el&&el.closest?el.closest('tr[data-table-row-idx]'):null;}
     function tblFor(el){return el&&el.closest?el.closest('[data-table-dnd]'):null;}
     document.addEventListener('dragstart',function(e){
       if(!(e.target.closest&&e.target.closest('.widget-table-row-drag-handle')))return;
       var row=rowFor(e.target); if(!row)return;
       e.dataTransfer.setData(T,row.getAttribute('data-table-row-idx'));
       e.dataTransfer.effectAllowed='move'; row.classList.add('table-row-drag-from');
     });
     document.addEventListener('dragend',function(e){
       var r=rowFor(e.target); if(r)r.classList.remove('table-row-drag-from');
       document.querySelectorAll('.table-row-drag-to').forEach(function(x){x.classList.remove('table-row-drag-to');});
     });
     document.addEventListener('dragover',function(e){
       if(rowFor(e.target)&&e.dataTransfer.types.indexOf(T)>=0){e.preventDefault();e.dataTransfer.dropEffect='move';}
     });
     document.addEventListener('dragenter',function(e){
       var r=rowFor(e.target); if(r&&e.dataTransfer.types.indexOf(T)>=0){e.preventDefault();r.classList.add('table-row-drag-to');}
     });
     document.addEventListener('dragleave',function(e){
       var r=rowFor(e.target); if(r)r.classList.remove('table-row-drag-to');
     });
     document.addEventListener('drop',function(e){
       var row=rowFor(e.target),tbl=tblFor(e.target);
       if(!row||!tbl||e.dataTransfer.types.indexOf(T)<0)return;
       e.preventDefault(); row.classList.remove('table-row-drag-to');
       var from=e.dataTransfer.getData(T),to=row.getAttribute('data-table-row-idx');
       if(from&&to&&from!==to){
         fetch(tbl.getAttribute('data-coll-base')+'/move-row?from='+from+'&to='+to,{method:'POST',headers:{'datastar-request':'true'}});
       }
     });
   })();")

(def ^:private column-dnd-js
  "HTML5 column drag-and-drop for reorder + drag-outside-to-remove."
  "(function(){
     var COL_TYPE='table-column';
     var COL_REORDER='table-reorderable-col';
     var COL_REMOVE='table-removable-col';
     function $(sel){return document.querySelector(sel);}
     function setup(){
       var tbl=$('[data-table-dnd]');
       if(!tbl){setTimeout(setup,100);return;}
       var base=tbl.getAttribute('data-coll-base');
       function thFor(el){return el.closest ? el.closest('th[data-col-idx]') : null;}

       tbl.addEventListener('dragstart',function(e){
         var th=thFor(e.target);
         if(!th)return;
         var idx=th.getAttribute('data-col-idx');
         var path=th.getAttribute('data-col-path');
         var reorderable=th.getAttribute('data-col-reorderable')==='true';
         var removable=th.getAttribute('data-col-removable')==='true';
         var payload={idx:idx,path:path,reorderable:reorderable,removable:removable};
         e.dataTransfer.setData(COL_TYPE,JSON.stringify(payload));
         if(reorderable)e.dataTransfer.setData(COL_REORDER,'1');
         if(removable)e.dataTransfer.setData(COL_REMOVE,'1');
         e.dataTransfer.effectAllowed='move';
         th.classList.add('table-col-drag-from');
       });
       tbl.addEventListener('dragend',function(e){
         var th=thFor(e.target);
         if(th)th.classList.remove('table-col-drag-from');
         tbl.querySelectorAll('.table-col-drag-to').forEach(function(el){el.classList.remove('table-col-drag-to');});
       });
       tbl.addEventListener('dragover',function(e){
         if(e.dataTransfer.types.indexOf(COL_TYPE)>=0){
           e.preventDefault();
           e.dataTransfer.dropEffect=e.dataTransfer.types.indexOf(COL_REORDER)>=0?'move':'none';
         }
       });
       tbl.addEventListener('dragenter',function(e){
         var th=thFor(e.target);
         if(th&&e.dataTransfer.types.indexOf(COL_REORDER)>=0){
           e.preventDefault();th.classList.add('table-col-drag-to');
         }
       });
       tbl.addEventListener('dragleave',function(e){
         var th=thFor(e.target);
         if(th)th.classList.remove('table-col-drag-to');
       });
       tbl.addEventListener('drop',function(e){
         e.preventDefault();e.stopPropagation();
         var th=thFor(e.target);
         if(!th||e.dataTransfer.types.indexOf(COL_REORDER)<0)return;
         th.classList.remove('table-col-drag-to');
         var src=JSON.parse(e.dataTransfer.getData(COL_TYPE));
         var dstPath=th.getAttribute('data-col-path');
         if(src.path!==dstPath){
           var paths=[].slice.call(tbl.querySelectorAll('th[data-col-path]'))
                       .map(function(t){return t.getAttribute('data-col-path');})
                       .filter(function(p){return p!==src.path;});
           var di=paths.indexOf(dstPath); if(di<0)di=paths.length;
           paths.splice(di,0,src.path);
           fetch(base+'/columns-move?order='+encodeURIComponent(paths.join(',')),{method:'POST',headers:{'datastar-request':'true'}})
             .then(function(r){return r.text();})
             .then(function(html){
               var tmp=document.createElement('div');
               tmp.innerHTML=html;
               var newTbl=tmp.querySelector('[data-table-dnd]');
               if(newTbl)tbl.parentNode.replaceChild(newTbl,tbl);
             });
         }
       });
       // Column remove: drag outside table
       document.addEventListener('dragover',function(e){
         if(e.dataTransfer.types.indexOf(COL_REMOVE)>=0){
           e.preventDefault();e.dataTransfer.dropEffect='move';
         }
       });
       document.addEventListener('drop',function(e){
         if(e.dataTransfer.types.indexOf(COL_REMOVE)>=0){
           e.preventDefault();
           var src=JSON.parse(e.dataTransfer.getData(COL_TYPE));
           fetch(base+'/columns-remove?path='+encodeURIComponent(src.path),{method:'POST',headers:{'datastar-request':'true'}})
             .then(function(r){return r.text();})
             .then(function(html){
               var tmp=document.createElement('div');
               tmp.innerHTML=html;
               var newTbl=tmp.querySelector('[data-table-dnd]');
               if(newTbl)tbl.parentNode.replaceChild(newTbl,tbl);
             });
         }
       });
     }
     if(document.readyState==='complete')setup();
     else window.addEventListener('DOMContentLoaded',setup);
   })();")

(def ^:private horizontal-scroll-js
  "Wheel handler: redirects vertical scroll to horizontal when over the table."
  "(function(){
     function setup(){
       var el=document.querySelector('[data-horizontal-scroll]');
       if(!el){setTimeout(setup,100);return;}
       el.addEventListener('wheel',function(e){
         var r=el.getBoundingClientRect();
         if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)return;
         var max=el.scrollWidth-el.offsetWidth;
         var nx=Math.max(0,Math.min(max,el.scrollLeft+e.deltaY));
         if(nx!==el.scrollLeft){el.scrollLeft=nx;e.preventDefault();}
       },{passive:false});
     }
     if(document.readyState==='complete')setup();
     else window.addEventListener('DOMContentLoaded',setup);
   })();")

;; ═══════════════════════════════════════════════════════════════════════════
;; cell renderer
;; ═══════════════════════════════════════════════════════════════════════════

(defn- render-cell
  [ctx coll-id idx item-values field-opts cell-node]
  (render/render-node
   (assoc ctx :item {:coll coll-id :idx idx} :values item-values :field-opts field-opts)
   cell-node))

;; ═══════════════════════════════════════════════════════════════════════════
;; vertical row
;; ═══════════════════════════════════════════════════════════════════════════

(defn- vertical-row
  [ctx coll-id columns items order opts idx]
  (let [{:keys [row-controls? numbered? can-delete-rows? field-opts
                lock-granularity read-only can-move-rows?]} opts
        table-path     [coll-id]
        item-values    (get items idx)
        row-path       (conj table-path idx)
        row-read-only? (boolean (or read-only (get-in opts [:row-read-only idx])))]
    [:tr
     ;; stable id so a re-ordering morph relocates the whole row (with its bound
     ;; inputs) instead of reconciling cell contents positionally
     {:id (str "row-" (name coll-id) "-" idx)
      :class (str (when row-read-only? "read-only"))
      "data-table-row-idx" (str idx)}
     (when row-controls?
       [:td.widget-table-row-controls
        ;; a draggable grip handle (the <tr> itself isn't draggable, so text in
        ;; the cell inputs stays selectable); row-dnd-js finds the row via
        ;; closest('tr[data-table-row-idx]') from the handle
        (when (and can-move-rows? (not row-read-only?))
          [:span.widget-table-row-drag-handle
           {:draggable "true" :title "Drag to reorder"} "\u2630"])
        (when (and can-delete-rows? (not row-read-only?))
          [:span.widget-table-row-remove-control
           {:title "Remove row"
            "data-on:click" (str "@post('" (coll-base ctx coll-id) "/" idx "/remove')")}
           [:span "\u2715"]])])
     (when numbered? [:td.widget-table-row-number (inc (.indexOf order idx))])
     (for [{:keys [path] :as col} columns
           :let [node (build-cell-node {:lock-granularity lock-granularity}
                                       table-path col row-path row-read-only?)]]
       [:td (render-cell ctx coll-id idx item-values field-opts node)])]))

;; ═══════════════════════════════════════════════════════════════════════════
;; vertical table
;; ═══════════════════════════════════════════════════════════════════════════

(defn- vertical-table
  [ctx coll-id columns items order opts]
  (let [{:keys [row-controls? numbered?
                can-add-rows? can-clear-table? read-only can-move-rows?
                add-button-label clear-button-label
                paged? page-size
                fixed-layout? bordered? striped? hover-highlight? condensed?
                current-sort-col current-sort-dir
                customizable?]} opts
        base      (coll-base ctx coll-id)
        colspan   (+ (count columns) (if row-controls? 1 0) (if numbered? 1 0))
        dnd?      (or can-move-rows? customizable?
                      (some :reorderable? columns) (some :removable? columns))]
    (list
     (when can-move-rows? [:script (h/raw row-dnd-js)])
     (when (or customizable? (some :reorderable? columns) (some :removable? columns))
       [:script (h/raw column-dnd-js)])
     [:div.widget-table-vertical
      [:table.table
       (merge
        {:class (str/join " " (clojure.core/filter some?
                               [(when fixed-layout? "table-fixed")
                                (when bordered? "table-bordered")
                                (when hover-highlight? "table-hover")
                                (when striped? "table-striped")
                                (when condensed? "table-condensed")]))}
        (when dnd? {"data-table-dnd" "true" "data-coll-base" base}))
       [:thead
        [:tr
         (when row-controls? [:th.widget-table-row-controls ""])
         (when numbered? [:th.widget-table-row-number "#"])
         (for [col columns]
           (if (:sortable? col)
             (sort-header-cell ctx coll-id col current-sort-col current-sort-dir)
             (let [th-attrs (cond-> {}
                              (or (:reorderable? col) (:removable? col))
                              (assoc :draggable "true"
                                     "data-col-idx" (str (.indexOf columns col))
                                     "data-col-path" (name (:path col)))
                              (:reorderable? col) (assoc "data-col-reorderable" "true")
                              (:removable? col)   (assoc "data-col-removable" "true"))]
               [:th th-attrs
                (if (and customizable? (:editable-label? col))
                  (editable-label ctx coll-id col)
                  (str (or (:label col) (name (:path col)))))])))]]
       [:tbody
        (map (partial vertical-row ctx coll-id columns items order opts) order)]
       (when (or paged? can-add-rows? can-clear-table? customizable?)
         [:tfoot
          [:tr
           [:td {:colspan colspan}
            [:div.widget-table-bottom-controls
             (into [:div.widget-table-control-buttons]
                   (clojure.core/filter some?
                     [(when (and can-add-rows? (not read-only))
                        [:button.btn.btn-primary.btn-sm.widget-table-add-row
                         {"data-on:click" (str "@post('" base "/add')")}
                         (or add-button-label "Add Row")])
                      (when (and can-clear-table? (not read-only))
                        [:button.btn.btn-outline-danger.btn-sm.widget-table-clear
                         {"data-on:click" (str "@post('" base "/clear')")}
                         (or clear-button-label "Clear")])
                      ;; restore the most-recently-hidden column (inverse of the
                      ;; drag-outside-to-remove); only shown when a column is hidden
                      (when (and customizable? (not read-only)
                                 (seq (get-in ctx [:view-state coll-id :cols :hidden])))
                        [:button.btn.btn-outline-secondary.btn-sm.widget-table-add-column
                         {"data-on:click" (str "@post('" base "/columns-add')")}
                         "↺ Restore column"])]))
             (when paged? (page-controls ctx coll-id (:page opts 0) (:pages opts 1)))]]]])]])))

;; ═══════════════════════════════════════════════════════════════════════════
;; horizontal table
;; ═══════════════════════════════════════════════════════════════════════════

(defn- horizontal-table
  [ctx coll-id columns items order opts]
  (let [{:keys [row-controls? numbered?
                can-add-rows? can-clear-table? read-only can-move-rows?
                add-button-label clear-button-label
                paged? striped? hover-highlight?
                current-sort-col current-sort-dir
                customizable?]} opts
        base (coll-base ctx coll-id)]
    (list
     [:script (h/raw horizontal-scroll-js)]
     (when (or customizable? (some :reorderable? columns) (some :removable? columns))
       [:script (h/raw column-dnd-js)])
     [:div.widget-table-horizontal
      {:data-horizontal-scroll "true"}
      [:table.table.table-bordered.horizontal-table
       {:class (str/join " " (clojure.core/filter some?
                              [(when striped? "horizontal-table-striped")
                               (when hover-highlight? "horizontal-table-hover")]))}
       [:colgroup (for [idx (range (inc (count order)))] [:col])]
       [:tbody
        ;; Row controls column
        (when row-controls?
          [:tr
           [:th {:scope "row"} "ctrls"]
           (for [idx order
                 :let [row-read-only? (boolean (or read-only (get-in opts [:row-read-only idx])))]]
             [:td.widget-table-row-controls.text-center
              (when (and (not row-read-only?) (:can-delete-rows? opts))
                [:span.widget-table-row-remove-control
                 {:title "Remove row"
                  "data-on:click" (str "@post('" base "/" idx "/remove')")}
                 [:span "\u2715"]])])])
        ;; Numbered column
        (when numbered?
          [:tr
           [:th {:scope "row"} "#"]
           (for [idx order]
             [:td.widget-table-row-number-horizontal.text-center
              (inc (.indexOf order idx))])])
        ;; One row per field
        (for [col columns
              :let [col-label (or (:label col) (name (:path col)))]]
          [:tr
           [:th.widget-table-row-header {:scope "row"}
            (str col-label)
            (when (and (:sortable? col)
                       (= (name (:path col)) (when current-sort-col (name current-sort-col))))
              [:span.widget-table-sort-direction (sort-indicator current-sort-dir)])]
           (for [idx order
                 :let [item-values (get items idx)
                       row-path [coll-id idx]
                       row-read-only? (boolean (or read-only (get-in opts [:row-read-only idx])))
                       node (build-cell-node {:lock-granularity (:lock-granularity opts :cell)}
                                             [coll-id] col row-path row-read-only?)]]
             [:td (render-cell ctx coll-id idx item-values (:field-opts opts) node)])])]]
      ;; Bottom controls
      (when (or paged? can-add-rows? can-clear-table? customizable?)
        [:div.widget-table-bottom-controls
         (into [:div.widget-table-control-buttons]
               (clojure.core/filter some?
                 [(when (and can-add-rows? (not read-only))
                    [:button.btn.btn-primary.btn-sm.widget-table-add-row
                     {"data-on:click" (str "@post('" base "/add')")}
                     (or add-button-label "Add Row")])
                  (when (and can-clear-table? (not read-only))
                    [:button.btn.btn-outline-danger.btn-sm.widget-table-clear
                     {"data-on:click" (str "@post('" base "/clear')")}
                     (or clear-button-label "Clear")])
                  (when (and customizable? (not read-only))
                    [:button.btn.btn-outline-secondary.btn-sm.widget-table-add-column
                     {"data-on:click" (str "@post('" base "/columns-add')")}
                     "Add Column"])]))
         (when paged? (page-controls ctx coll-id (:page opts 0) (:pages opts 1)))])])))

;; ═══════════════════════════════════════════════════════════════════════════
;; main render
;; ═══════════════════════════════════════════════════════════════════════════

(defmethod render-widget :stepvine.components/table
  [ctx _component
   {:keys [id label columns lock-granularity
           row-controls? numbered?
           can-add-rows? can-delete-rows? can-move-rows? can-clear-table?
           add-button-label clear-button-label
           read-only paged? page-size
           filter
           fixed-layout? bordered? striped? hover-highlight? condensed?
           customizable?
           horizontal?
           current-sort-col current-sort-dir]
    :or   {lock-granularity :cell
           can-add-rows?    true
           can-delete-rows? true
           can-move-rows?   (not horizontal?)
           page-size        100
           bordered?        true
           hover-highlight? true}
    :as   opts}
   _body]
  (let [coll-id   id
        {:keys [order field-opts items]} (get-in ctx [:collections coll-id])
        columns   (or (seq columns)
                      (mapv (fn [[fid fopts]]
                              {:path fid
                               :label (or (:label fopts) (name fid))})
                            field-opts))
        ;; view-state column overlay (§jj9): reorder / hide / relabel columns
        columns   (apply-column-overlay columns (get-in ctx [:view-state coll-id :cols]))
        ;; server-side view-state: sort + paging applied to the (already row-
        ;; ordered) collection; the displayed order/indicators come from here.
        view      (apply-view ctx coll-id order items {:page-size page-size :paged? paged?})
        merged    (merge
                   {:row-controls?       (or row-controls?
                                            can-add-rows? can-delete-rows? can-move-rows?)
                    :numbered?           numbered?
                    :can-add-rows?       can-add-rows?
                    :can-delete-rows?    can-delete-rows?
                    :can-move-rows?      (and can-move-rows? (not read-only))
                    :can-clear-table?    can-clear-table?
                    :add-button-label    add-button-label
                    :clear-button-label  clear-button-label
                    :lock-granularity    lock-granularity
                    :paged?              paged?
                    :page-size           page-size
                    :filter              filter
                    :fixed-layout?       fixed-layout?
                    :bordered?           bordered?
                    :striped?            striped?
                    :hover-highlight?    hover-highlight?
                    :condensed?          condensed?
                    :customizable?       customizable?
                    :field-opts          field-opts
                    :read-only           read-only}
                   opts
                   {:current-sort-col (get-in view [:sort :col])
                    :current-sort-dir (get-in view [:sort :dir])
                    :page             (:page view)
                    :pages            (:pages view)})]
    ;; stable id so the re-render (patch-elements morph) can target this table
    [:div.widget.widget-table {:id (str "coll-" (name coll-id))}
     (when label
       (if (string? label)
         [:label.widget-table-label label]
         [:div.widget-table-label label]))
     (when filter (filter-dropdown ctx coll-id filter))
     (if horizontal?
       (horizontal-table ctx coll-id columns items (:order view) merged)
       (vertical-table ctx coll-id columns items (:order view) merged))]))
