// Table widget client behaviour: row drag-and-drop, column drag-and-drop
// (reorder + drag-out-to-remove), and wheel-to-horizontal-scroll.
//
// Loaded once per page (form.html). Each rendered table fragment emits a tiny
// inline `<script>svRowDnd()</script>` (etc.) call — Datastar re-executes those
// inline scripts on every re-render, so a freshly patched-in table re-attaches
// its listeners. svRowDnd is guarded to install its document-delegated listeners
// only once; svColumnDnd / svHorizontalScroll re-run setup against the current
// table element (which is replaced on each re-render).
//
// Source of truth for the table widget markup that these wire up:
// src/clj/yogthos/stepvine/components/widgets/tables/table.clj

// HTML5 row drag-and-drop via the row grip handle. Listeners are delegated on
// `document` and installed once (the table element is replaced on every
// re-render, so table-bound listeners would be lost). The drop POSTs move-row
// with the Datastar-Request header (the route requires it); the reorder is
// applied via the server's SSE patch-elements broadcast.
window.svRowDnd = function () {
  if (window.__svRowDnd) return; window.__svRowDnd = true;
  var T = 'table-row';
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
};

// HTML5 column drag-and-drop for reorder + drag-outside-to-remove.
window.svColumnDnd = function () {
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
};

// Wheel handler: redirects vertical scroll to horizontal when over the table.
window.svHorizontalScroll = function () {
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
};
