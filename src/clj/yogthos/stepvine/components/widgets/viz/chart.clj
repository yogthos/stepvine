(ns yogthos.stepvine.components.widgets.viz.chart
  "Chart widget — a Highcharts line/column/… chart whose series tracks a signal.

   The reactive bridge is a plain Datastar attribute binding: the container's
   `data-series` attribute is bound to the `<id>_data` signal (a JSON array
   string). An inline script initialises Highcharts from that attribute and a
   MutationObserver pushes later signal updates into the chart — so no global
   Datastar handle is needed (Datastar v1 exposes none to external scripts).
   Title, axes and legend are static config baked in at render time."
  (:require
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(def ^:private chart-js-template
  "%1$s = chart-id, %2$s = chart type, %3$s = title, %4$s = xAxis snippet,
   %5$s = yAxis snippet, %6$s = legend name."
  "(function(){
     var el=document.getElementById('%1$s');
     if(!el)return;
     function series(){try{return JSON.parse(el.getAttribute('data-series')||'[]');}catch(e){return [];}}
     function init(){
       if(!window.Highcharts)return setTimeout(init,100);
       el._hc=new Highcharts.Chart({
         chart:{type:'%2$s',renderTo:el},
         title:{text:'%3$s'},
         %4$s
         %5$s
         series:[{name:'%6$s',data:series()}],
         credits:{enabled:false}});
       new MutationObserver(function(){
         if(el._hc&&el._hc.series[0]){el._hc.series[0].setData(series());}
       }).observe(el,{attributes:true,attributeFilter:['data-series']});
     }
     if(document.readyState!=='loading')init();
     else window.addEventListener('DOMContentLoaded',init);
   })();")

(defmethod render-widget :stepvine.components/chart
  [ctx _component {:keys [id label chart-type x-axis-title y-axis-title legend]} _body]
  (let [sig      (render/item-signal-name ctx id)
        chart-id (str "chart-" (name id))
        data-key (keyword (str (name id) "-data"))
        ;; SSR seed of the series (reaction or field named <id>-data), then a
        ;; reactive binding to the <id>_data signal for live updates.
        data-now (str (or (get-in ctx [:rxns data-key])
                          (get-in ctx [:values data-key])
                          "[]"))]
    (list
     [:script {:src "https://cdn.jsdelivr.net/npm/highcharts@11/highcharts.js"}]
     [:div.widget.chart.field
      (when label [:label label])
      [:div.chart-canvas
       {:id chart-id
        :data-series data-now                                   ; SSR initial series
        "data-attr:data-series" (str "$" sig "_data || '[]'")}]] ; live updates
     [:script (h/raw (format chart-js-template
                             chart-id
                             (name (or chart-type :line))
                             (str (or label ""))
                             (if x-axis-title (str "xAxis:{title:{text:'" x-axis-title "'}},") "")
                             (if y-axis-title (str "yAxis:{title:{text:'" y-axis-title "'}},") "")
                             (str (or legend "data"))))])))
