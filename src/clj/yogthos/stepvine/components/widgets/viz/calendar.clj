(ns yogthos.stepvine.components.widgets.viz.calendar
  "Calendar/Schedule widget using @calendarjs/ce — a week schedule view that
   persists its edits to the document like every other widget.

   Events are a document field named `<id>-events` (a JSON string). The bridge:
     - read  — the container's `data-events` attribute tracks the `<id>_events`
               signal (Datastar `data-attr`); a MutationObserver pushes external
               updates (e.g. another viewer's edit) into the schedule.
     - write — the schedule's onupdate POSTs the edited events to the field
               endpoint with the `datastar-request` header, exactly as `@post`
               does, so the change is stored server-side and broadcast to all
               viewers.
   No global Datastar handle is used (Datastar v1 exposes none to scripts)."
  (:require
   [hiccup2.core :as h]
   [yogthos.stepvine.render :as render :refer [render-widget]]))

(def ^:private calendarjs-js-template
  "%1$s = cal-id, %2$s = field POST url, %3$s = events signal name."
  "(function(){
     var el=document.getElementById('%1$s');
     if(!el)return;
     var POST='%2$s', SIG='%3$s', lastWritten=null;
     function events(){try{return JSON.parse(el.getAttribute('data-events')||'[]');}catch(e){return [];}}
     function persist(evs){
       var j=JSON.stringify(evs); lastWritten=j;
       var body={}; body[SIG]=j;
       fetch(POST,{method:'POST',
                   headers:{'datastar-request':'true','Content-Type':'application/json'},
                   body:JSON.stringify(body)});
     }
     function init(){
       if(!window.calendarjs)return setTimeout(init,100);
       var start=new Date().toISOString().split('T')[0];
       el._caljs=calendarjs.Schedule(el,{type:'week',value:start,data:events(),
         validRange:['08:00','18:00'],
         onupdate:function(s,evs){persist(evs);}});
       new MutationObserver(function(){
         // apply external changes only — skip the echo of our own write
         if(el._caljs && el.getAttribute('data-events')!==lastWritten){el._caljs.setData(events());}
       }).observe(el,{attributes:true,attributeFilter:['data-events']});
     }
     if(document.readyState!=='loading')init();
     else window.addEventListener('DOMContentLoaded',init);
   })();")

(defmethod render-widget :stepvine.components/calendar
  [ctx _component {:keys [id label]} _body]
  (let [sig        (render/item-signal-name ctx id)
        cal-id     (str "caljs-" (name id))
        events-id  (keyword (str (name id) "-events"))
        ;; SSR seed: the stored JSON string for <id>-events (field or reaction)
        events-now (let [v (or (get-in ctx [:rxns events-id])
                               (get-in ctx [:values events-id]))]
                     (if (and (string? v) (seq v)) v "[]"))]
    (list
     [:link {:rel "stylesheet" :href "/vendor/calendarjs.min.css"}]
     [:script {:src "/vendor/lemonade.min.js"}]
     [:script {:src "/vendor/calendarjs.min.js"}]
     [:div.widget.calendar.field
      (when label [:h3 label])
      [:div.calendar-canvas
       {:id cal-id
        :data-events events-now
        "data-attr:data-events" (str "$" sig "_events || '[]'")}]]
     [:script (h/raw (format calendarjs-js-template
                             cal-id
                             (render/field-post-url ctx events-id)
                             (render/signal-name events-id)))])))
