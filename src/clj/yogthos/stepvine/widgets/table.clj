(ns yogthos.stepvine.widgets.table
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

(defn- coll-base [ctx coll-id]
  (str "/doc/" (:doc-id ctx) "/coll/" (name coll-id)))

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
     {:style {:cursor "pointer"} "data-on:click" sort-expr}
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
      (str "@post('" (coll-base ctx coll-id) "/columns-label?col=" (name path) "')")}]))

;; ═══════════════════════════════════════════════════════════════════════════
;; filter UI
;; ═══════════════════════════════════════════════════════════════════════════

(defn- filter-dropdown
  [ctx coll-id {:keys [label] :or {label "Filter:"}}]
  (let [filter-id   (keyword (str (name coll-id) "-filter"))
        sig         (render/item-signal-name ctx filter-id)
        filter-opts (get-in ctx [:options filter-id])]
    [:div.widget-table-filter
     [:label label]
     [:select {"data-bind" sig
               "data-on:change" (str "@post('" (coll-base ctx coll-id) "/filter')")}
      [:option {:value ""} "All"]
      (for [opt (or filter-opts [])
            :let [v (if (vector? opt) (second opt) opt)
                  l (if (vector? opt) (first opt) opt)]]
        [:option {:value (str v)} (str l)])]]))

;; ═══════════════════════════════════════════════════════════════════════════
;; paging
;; ═══════════════════════════════════════════════════════════════════════════

(defn- page-controls
  [ctx coll-id]
  (let [sig (render/item-signal-name ctx coll-id)]
    [:div.widget-table-pager
     [:nav [:ul.pagination
            [:li.page-item
             [:a.page-link {:href "#"
                            "data-on:click"
                            (str "$" sig "_page = Math.max(0, ($" sig "_page || 0) - 1); "
                                 "@post('" (coll-base ctx coll-id) "/page')")}
              "«"]]
            [:li.page-item.disabled
             [:span.page-link {"data-text" (str "$" sig "_page_info")} ""]]
            [:li.page-item
             [:a.page-link {:href "#"
                            "data-on:click"
                            (str "$" sig "_page = ($" sig "_page || 0) + 1; "
                                 "@post('" (coll-base ctx coll-id) "/page')")}
              "»"]]]]]))

;; ═══════════════════════════════════════════════════════════════════════════
;; inline JS for drag-and-drop + horizontal scroll
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private row-dnd-js
  "HTML5 row drag-and-drop. Uses fetch to POST move-row; the server's SSE
   response re-renders the updated table."
  "(function(){
     var DRAG_TYPE='table-row';
     function setup(){
       var tbl=document.querySelector('[data-table-dnd]');
       if(!tbl){setTimeout(setup,100);return;}
       var base=tbl.getAttribute('data-coll-base');
       function rowFor(el){return el.closest ? el.closest('tr[data-table-row-idx]') : null;}
       tbl.addEventListener('dragstart',function(e){
         var row=rowFor(e.target);
         if(!row)return;
         e.dataTransfer.setData(DRAG_TYPE,row.getAttribute('data-table-row-idx'));
         e.dataTransfer.effectAllowed='move';
         row.classList.add('table-row-drag-from');
       });
       tbl.addEventListener('dragend',function(e){
         var row=rowFor(e.target);
         if(row)row.classList.remove('table-row-drag-from');
         tbl.querySelectorAll('.table-row-drag-to').forEach(function(r){r.classList.remove('table-row-drag-to');});
       });
       tbl.addEventListener('dragover',function(e){
         if(e.dataTransfer.types.indexOf(DRAG_TYPE)>=0){
           e.preventDefault();e.dataTransfer.dropEffect='move';
         }
       });
       tbl.addEventListener('dragenter',function(e){
         var row=rowFor(e.target);
         if(row&&e.dataTransfer.types.indexOf(DRAG_TYPE)>=0){
           e.preventDefault();row.classList.add('table-row-drag-to');
         }
       });
       tbl.addEventListener('dragleave',function(e){
         var row=rowFor(e.target);
         if(row)row.classList.remove('table-row-drag-to');
       });
       tbl.addEventListener('drop',function(e){
         e.preventDefault();e.stopPropagation();
         var row=rowFor(e.target);
         if(!row)return;
         row.classList.remove('table-row-drag-to');
         var from=e.dataTransfer.getData(DRAG_TYPE);
         var to=row.getAttribute('data-table-row-idx');
         if(from&&to&&from!==to){
           fetch(base+'/move-row?from='+from+'&to='+to,{method:'POST'})
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
         var reorderable=th.getAttribute('data-col-reorderable')==='true';
         var removable=th.getAttribute('data-col-removable')==='true';
         var payload={idx:idx,reorderable:reorderable,removable:removable};
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
         var dst=th.getAttribute('data-col-idx');
         if(src.idx!==dst){
           fetch(base+'/columns-move?from='+src.idx+'&to='+dst,{method:'POST'})
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
           fetch(base+'/columns-remove?idx='+src.idx,{method:'POST'})
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
     {:class (str (when row-read-only? "read-only"))
      "data-table-row-idx" (str idx)}
     (when row-controls?
       [:td.widget-table-row-controls
        (when can-delete-rows?
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
                              (:reorderable? col)
                              (assoc :draggable "true"
                                     "data-col-idx" (str (.indexOf columns col))
                                     "data-col-reorderable" "true")
                              (:removable? col)
                              (assoc :draggable "true"
                                     "data-col-idx" (str (.indexOf columns col))
                                     "data-col-removable" "true"))]
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
                      (when (and customizable? (not read-only))
                        [:button.btn.btn-outline-secondary.btn-sm.widget-table-add-column
                         {"data-on:click" (str "@post('" base "/columns-add')")}
                         "Add Column"])]))
             (when paged? (page-controls ctx coll-id))]]]])]])))

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
      {:data-horizontal-scroll "true"
       :style {:overflow-x "auto" :overflow-y "visible"}}
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
           [:th {:scope "row" :style {:white-space "nowrap"}}
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
         (when paged? (page-controls ctx coll-id))])])))

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
                    :current-sort-col    current-sort-col
                    :current-sort-dir    current-sort-dir
                    :field-opts          field-opts
                    :read-only           read-only}
                   opts)]
    [:div.widget.widget-table
     (when label
       (if (string? label)
         [:label.widget-table-label label]
         [:div.widget-table-label label]))
     (when filter (filter-dropdown ctx coll-id filter))
     (if horizontal?
       (horizontal-table ctx coll-id columns items order merged)
       (vertical-table ctx coll-id columns items order merged))]))
