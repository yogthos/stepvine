(ns yogthos.stepvine.widgets.viz.calendar
  "Calendar/Schedule widget using @calendarjs/ce.
   Renders an event schedule (day/week view) driven by Datastar signals.
   Loads CalendarJS via CDN and initializes from signal data."
  (:require
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(def ^:private calendarjs-js-template
  "JS template for CalendarJS Schedule init. %1$s = cal-id, %2$s = signal name."
  "(function(){
     function init(){
       var el=document.getElementById('%1$s');
       if(!el||!window.calendarjs)return setTimeout(init,100);
       var data=JSON.parse(DS.$%2$s_events||'[]');
       var cal=calendarjs.Schedule(el,{
         type:'week',
         value:DS.$%2$s_value||new Date().toISOString().split('T')[0],
         data:data,
         validRange:['08:00','18:00'],
         onupdate:function(s,events){
           DS.$%2$s_events=JSON.stringify(events);
         }});
       el._caljs=cal;
     }
     DS.$on('%2$s_events',function(v){
       var el=document.getElementById('%1$s');
       if(el&&el._caljs){
         el._caljs.setData(JSON.parse(v||'[]'));
       }});
     if(document.readyState==='complete')init();
     else window.addEventListener('load',init);
   })();")

(defmethod render-widget :stepvine.components/calendar
  [ctx _component {:keys [id label]} _body]
  (let [sig    (render/item-signal-name ctx id)
        cal-id (str "caljs-" (name id))]
    (list
     [:link {:rel "stylesheet"
             :href "https://cdn.jsdelivr.net/npm/@calendarjs/ce/dist/style.min.css"}]
     [:script {:src "https://cdn.jsdelivr.net/npm/lemonadejs/dist/lemonade.min.js"}]
     [:script {:src "https://cdn.jsdelivr.net/npm/@calendarjs/ce/dist/index.min.js"}]
     [:div.widget.calendar.field
      (when label [:h3 label])
      [:div.calendar-canvas {:id cal-id}]]
     [:script (h/raw (format calendarjs-js-template cal-id sig))])))
