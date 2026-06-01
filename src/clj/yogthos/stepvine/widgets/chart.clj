(ns yogthos.stepvine.widgets.chart
  "Chart widget — renders a Highcharts chart driven by Datastar signals.
   The widget emits a container div, a Highcharts CDN <script> tag, and an inline
   script that reads signal data to initialize/update the chart reactively."
  (:require
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(def ^:private chart-js-template
  "JS template for Highcharts init. %1$s = chart-id, %2$s = signal name, %3$s = chart type,
   %4$s = xAxis title snippet, %5$s = yAxis title snippet."
  "(function(){
     function init(){
       var el=document.getElementById('%1$s');
       if(!el||!window.Highcharts)return setTimeout(init,100);
       var cfg={
         chart:{type:'%3$s',renderTo:el},
         title:{text:DS.$%2$s_title||''},
         %4$s
         %5$s
         series:[{name:DS.$%2$s_legend||'data',
                  data:JSON.parse(DS.$%2$s_data||'[]')}],
         credits:{enabled:false}};
       if(el._hc)el._hc.destroy();
       el._hc=new Highcharts.Chart(cfg);
     }
     DS.$on('%2$s_data',function(){
       var el=document.getElementById('%1$s');
       if(el&&el._hc&&el._hc.series[0]){
         el._hc.series[0].setData(JSON.parse(DS.$%2$s_data||'[]'));
       }});
     if(document.readyState==='complete')init();
     else window.addEventListener('load',init);
   })();")

(defmethod render-widget :stepvine.components/chart
  [ctx _component {:keys [id label chart-type x-axis-title y-axis-title]} _body]
  (let [sig      (render/item-signal-name ctx id)
        chart-id (str "chart-" (name id))]
    (list
     [:script {:src "https://cdn.jsdelivr.net/npm/highcharts@11/highcharts.js"}]
     [:div.widget.chart.field
      (when label [:label label])
      [:div {:id chart-id :style "width:100%;min-height:300px;"}]]
     [:script (h/raw (format chart-js-template
                             chart-id
                             sig
                             (name (or chart-type :line))
                             (if x-axis-title
                               (str "xAxis:{title:{text:'" x-axis-title "'}},")
                               "")
                             (if y-axis-title
                               (str "yAxis:{title:{text:'" y-axis-title "'}},")
                               "")))])))
