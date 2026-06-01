(ns yogthos.stepvine.components.widgets.viz.calendar
  "Calendar/Schedule widget using @calendarjs/ce — a week schedule view.

   Like :chart, event data is bridged through a Datastar attribute binding: the
   container's `data-events` attribute tracks the `<id>_events` signal (a JSON
   array), so the schedule can be driven from the server with no global Datastar
   handle. With no such signal it simply shows the current week, empty. The start
   date follows the `<id>_value` signal when present, else today."
  (:require
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(def ^:private calendarjs-js-template
  "%1$s = cal-id."
  "(function(){
     var el=document.getElementById('%1$s');
     if(!el)return;
     function events(){try{return JSON.parse(el.getAttribute('data-events')||'[]');}catch(e){return [];}}
     function init(){
       if(!window.calendarjs)return setTimeout(init,100);
       var start=el.getAttribute('data-value')||new Date().toISOString().split('T')[0];
       el._caljs=calendarjs.Schedule(el,{type:'week',value:start,data:events(),validRange:['08:00','18:00']});
       new MutationObserver(function(){
         if(el._caljs){el._caljs.setData(events());}
       }).observe(el,{attributes:true,attributeFilter:['data-events']});
     }
     if(document.readyState!=='loading')init();
     else window.addEventListener('DOMContentLoaded',init);
   })();")

(defmethod render-widget :stepvine.components/calendar
  [ctx _component {:keys [id label]} _body]
  (let [sig       (render/item-signal-name ctx id)
        cal-id    (str "caljs-" (name id))
        events-now (str (or (get-in ctx [:rxns (keyword (str (name id) "-events"))])
                            (get-in ctx [:values (keyword (str (name id) "-events"))])
                            "[]"))]
    (list
     [:link {:rel "stylesheet"
             :href "https://cdn.jsdelivr.net/npm/@calendarjs/ce/dist/style.min.css"}]
     [:script {:src "https://cdn.jsdelivr.net/npm/lemonadejs/dist/lemonade.min.js"}]
     [:script {:src "https://cdn.jsdelivr.net/npm/@calendarjs/ce/dist/index.min.js"}]
     [:div.widget.calendar.field
      (when label [:h3 label])
      [:div.calendar-canvas
       {:id cal-id
        :data-events events-now
        "data-attr:data-events" (str "$" sig "_events || '[]'")
        "data-attr:data-value"  (str "$" sig "_value || ''")}]]
     [:script (h/raw (format calendarjs-js-template cal-id))])))
