(ns yogthos.stepvine.web.editor
  "Live app/form editor (admin) — a split-panel page: a CodeMirror EDN+CSS editor
   on the left, a live preview iframe on the right. Apps are EDN + CSS in the DB
   (`:store/forms`), so saving re-skins/re-shapes the app live with no redeploy.

   The preview renders the edited form to *static* HTML (an ephemeral session, no
   SSE) so structure + styling show as you type. Preview/save POST JSON and are
   guarded by the datastar header (not anti-forgery), like the field endpoints."
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [jsonista.core :as json]
   [ring.util.response :as resp]
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.auth :as auth]
   [yogthos.stepvine.editor.impl :as impl]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.web.layout :as layout]
   [yogthos.stepvine.web.security :as security]))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn keyword}))

;; --- live preview ---------------------------------------------------------

(defn- preview-views
  "Ordered selectable view ids for the preview: the :pages order for a multi-page
   form, otherwise the view keys (with :default first when present)."
  [form]
  (if (seq (:pages form))
    (mapv (comp keyword :view) (:pages form))
    (let [vs (mapv keyword (keys (:views form)))]
      (if (some #{:default} vs) (vec (cons :default (remove #{:default} vs))) vs))))

(defn- resolve-preview-view
  "Which view to render: the requested one if it exists, else :default, else the
   first page / first view. nil when the form has no views."
  [form requested]
  (let [views (:views form)
        req-k (some-> requested not-empty keyword)]
    (cond
      (and req-k (get views req-k)) req-k
      (get views :default)          :default
      :else                         (first (preview-views form)))))

(defn preview-html
  "Render one view of the edited form to static HTML (no datastar/SSE), wrapped in
   the platform CSS + the edited app CSS. `view-id` selects the view (falling back
   to :default / the first page). Returns `{:html :views :view}` — the rendered
   document, the selectable view ids, and the one actually rendered. Parse/eval
   errors render as a message (with :views nil)."
  [forms-store options-store edn-str css view-id]
  (try
    (let [form (forms/prepare-form forms-store (edn/read-string edn-str))
          vid  (resolve-preview-view form view-id)
          sess (impl/create-session form {})
          ctx  (-> (render/session->context sess vid "preview")
                   (assoc :options (options/resolve-field-options
                                    options-store (render/all-field-opts sess))))
          view (render/render-view ctx (render/view-markup sess vid))]
      {:views (preview-views form)
       :view  vid
       :html
       (str "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            "<link rel=\"stylesheet\" href=\"/css/stepvine.css\">"
            ;; the preview is static HTML with no datastar running, so elements
            ;; datastar would hide until a signal goes truthy (the transient notice
            ;; bar, the submitted/read-only banner) would otherwise show. Hide that
            ;; lifecycle chrome so the preview reflects a fresh, editable document.
            "<style>.sv-notice,.sv-status{display:none!important}</style>"
            "<style>" (or css "") "</style></head>"
            "<body><div class=\"sv-doc-body\">" view "</div></body></html>")})
    (catch Throwable e
      {:views nil :view nil
       :html
       (str "<!DOCTYPE html><html><body style=\"font-family:ui-monospace,monospace;padding:1rem;color:#b91c1c\">"
            (h/html [:strong "Preview error"] [:pre (str (or (.getMessage e) e))])
            "</body></html>")})))

(defn preview-handler [forms-store options-store]
  (fn [req]
    (let [{:keys [edn css] vw :view} (json/read-value (:body req) json-mapper)
          {:keys [html views view]}  (preview-html forms-store options-store edn css vw)]
      {:status  200
       :headers (cond-> {"Content-Type" "text/html; charset=utf-8"}
                  ;; expose the available views + the rendered one so the editor
                  ;; can offer a per-view preview picker, kept in sync with the EDN
                  (seq views) (assoc "X-Preview-Views"   (str/join "," (map name views))
                                     "X-Preview-Current" (some-> view name)))
       :body    html})))

;; --- save -----------------------------------------------------------------

(defn save [forms-store]
  (fn [req]
    (let [{:keys [edn css]} (json/read-value (:body req) json-mapper)]
      (try
        (let [form (cond-> (edn/read-string edn)
                     (seq css) (assoc :css css)
                     (empty? css) (dissoc :css))]
          (forms/save-form! forms-store form)
          {:status 200 :headers {"Content-Type" "application/json"} :body "{\"ok\":true}"})
        (catch Throwable e
          {:status 400 :headers {"Content-Type" "application/json"}
           :body (json/write-value-as-string {:ok false :error (str (or (.getMessage e) e))})})))))

;; --- create ---------------------------------------------------------------

(defn- scaffold [id title]
  {:id      (keyword id)
   :title   (or (not-empty title) (name id))
   :version 1
   :data    {:model [] :reactions []}
   :views   {:default {:opts   {:widget-namespaces {"c" "stepvine.components"}}
                       :markup [:c/form {}
                                [:h1 (or (not-empty title) (name id))]
                                [:c/input-field {:id :name :label "Name"}]]}}})

(defn create-form [forms-store]
  (fn [req]
    (let [id (some-> (get-in req [:params :id]) str str/trim not-empty)]
      (if (and id (nil? (forms/raw-form forms-store (keyword id))))
        (do (forms/save-form! forms-store (scaffold id (get-in req [:params :title])))
            (resp/redirect (str "/admin/forms/" id "/edit") :see-other))
        (resp/redirect "/admin/forms" :see-other)))))

;; --- editor page ----------------------------------------------------------

(def ^:private editor-js
  "(async () => {
  // CodeMirror, bundled locally (vendor-build/) and served from /vendor — no CDN.
  const {V, S, L, C, HL, clojure, cssMode} = await import('/vendor/codemirror.js');
  const t = HL.tags;
  const hl = L.HighlightStyle.define([
    {tag:t.keyword,color:'#7c3aed'},{tag:t.atom,color:'#b45309'},{tag:t.number,color:'#b45309'},
    {tag:t.string,color:'#0d9488'},{tag:t.comment,color:'#9ca3af',fontStyle:'italic'},
    {tag:t.bracket,color:'#64748b'},{tag:t.propertyName,color:'#0369a1'},{tag:t.bool,color:'#b45309'}]);
  const theme = V.EditorView.theme({'&':{height:'100%',fontSize:'13px'},
    '.cm-scroller':{fontFamily:'ui-monospace,SFMono-Regular,Menlo,monospace'},'.cm-content':{padding:'8px 0'}});
  const j = id => JSON.parse(document.getElementById(id).textContent);
  let edn = j('edn-init'), css = j('css-init');
  let previewView = null;   // null => let the server pick (default / first page)
  const debounce=(f,ms)=>{let h;return(...a)=>{clearTimeout(h);h=setTimeout(()=>f(...a),ms)}};
  const preview = async () => {
    const r = await fetch('/admin/forms/preview',{method:'POST',
      headers:{'Content-Type':'application/json','datastar-request':'true'},
      body:JSON.stringify({edn,css,view:previewView})});
    document.getElementById('preview').srcdoc = await r.text();
    // keep the preview view picker in sync with the form's views
    const vs = r.headers.get('X-Preview-Views'), cur = r.headers.get('X-Preview-Current');
    const sel = document.getElementById('preview-view');
    if (sel) {
      const views = vs ? vs.split(',') : [];
      const sig = views.join(',');
      if (sel.dataset.sig !== sig) {       // rebuild options only when the set changes
        sel.dataset.sig = sig; sel.innerHTML='';
        views.forEach(v=>{const o=document.createElement('option');o.value=v;o.textContent=v;sel.appendChild(o);});
      }
      if (cur) { sel.value = cur; previewView = cur; }
      sel.closest('.ed-preview-bar').style.display = views.length > 1 ? 'flex' : 'none';
    }
  };
  const refresh = debounce(preview, 400);
  const mk = (el, doc, mode, set) => new V.EditorView({parent:el, state:S.EditorState.create({doc, extensions:[
    V.lineNumbers(), C.history(), L.bracketMatching(), L.foldGutter(), L.indentOnInput(),
    L.StreamLanguage.define(mode), L.syntaxHighlighting(hl), theme,
    V.keymap.of([...C.defaultKeymap, ...C.historyKeymap, C.indentWithTab]),
    V.EditorView.updateListener.of(u=>{if(u.docChanged){set(u.state.doc.toString());refresh();}})]})});
  mk(document.getElementById('edn-pane'), edn, clojure, v=>edn=v);
  mk(document.getElementById('css-pane'), css, cssMode, v=>css=v);
  document.querySelectorAll('.ed-tab').forEach(b=>b.onclick=()=>{
    document.querySelectorAll('.ed-tab').forEach(x=>x.classList.remove('active'));
    b.classList.add('active');
    document.getElementById('edn-pane').hidden = b.dataset.pane!=='edn';
    document.getElementById('css-pane').hidden = b.dataset.pane!=='css';
  });
  const psel = document.getElementById('preview-view');
  if (psel) psel.onchange = () => { previewView = psel.value; preview(); };
  document.getElementById('save').onclick = async () => {
    const r = await fetch(location.pathname.replace('/edit','/save'),{method:'POST',
      headers:{'Content-Type':'application/json','datastar-request':'true'},
      body:JSON.stringify({edn,css})});
    const m = document.getElementById('savemsg'); const ok = r.ok;
    m.textContent = ok ? 'Saved ✓' : ('Error: ' + ((await r.json()).error||'')); m.style.color = ok?'#16a34a':'#b91c1c';
    setTimeout(()=>m.textContent='', 4000);
  };
  preview();
})();")

(def ^:private editor-styles
  (str ".sv-content{max-width:none;padding:0}"
       ".ed-toolbar{display:flex;align-items:center;gap:1rem;padding:.5rem 1.25rem;border-bottom:1px solid #e5e7eb;flex-wrap:wrap}"
       ".ed-toolbar form{display:inline} .ed-toolbar input{padding:.25rem .4rem;border:1px solid #d1d5db;border-radius:.375rem}"
       ".ed-toolbar button{padding:.3rem .8rem;border:1px solid #d1d5db;border-radius:.375rem;background:#fff;cursor:pointer}"
       "#save{background:#2563eb;color:#fff;border-color:#2563eb} .muted{color:#6b7280}"
       ".ed-split{display:flex;height:calc(100vh - 160px);min-height:24rem}"
       ".ed-left{flex:1;display:flex;flex-direction:column;border-right:1px solid #e5e7eb;min-width:0}"
       ".ed-tabs{display:flex;border-bottom:1px solid #e5e7eb}"
       ".ed-tab{padding:.4rem .9rem;border:none;background:none;cursor:pointer;border-bottom:2px solid transparent;color:#374151}"
       ".ed-tab.active{border-bottom-color:#2563eb;color:#2563eb}"
       ".ed-pane{flex:1;overflow:auto;min-height:0} .cm-editor{height:100%}"
       ".ed-right{flex:1;display:flex;flex-direction:column;min-width:0}"
       ".ed-preview-bar{display:none;align-items:center;gap:.5rem;padding:.35rem .9rem;border-bottom:1px solid #e5e7eb;font-size:.85rem;color:#6b7280}"
       ".ed-preview-bar select{padding:.2rem .4rem;border:1px solid #d1d5db;border-radius:.375rem;background:#fff}"
       ".ed-preview{flex:1;border:none;min-width:0;background:#fff}"))

(defn- roles-str [roles] (str/join " " (sort (map name roles))))

(defn edit-page [forms-store access-store users-store]
  (fn [req]
    (let [id    (get-in req [:path-params :id])
          form  (forms/raw-form forms-store (keyword id))
          edn   (with-out-str (pprint/pprint (dissoc form :css)))
          css   (or (:css form) "")]
      (-> (resp/response
           (layout/page
            {:user   (auth/current-user users-store req)
             :title  (str "Edit " id)
             :crumbs [{:label "Documents" :href "/"} {:label "Admin" :href "/admin/users"}
                      {:label "Forms" :href "/admin/forms"} {:label id}]
             :head   [:style editor-styles]}
            [:div.ed-toolbar
             [:button#save "Save"]
             [:span#savemsg.muted]
             [:span.muted "·"]
             [:form {:method "post" :action (str "/admin/forms/" id "/roles")}
              (security/csrf-field)
              "roles: " [:input {:name "roles" :value (roles-str (access/form-roles access-store id)) :size 16}]
              " " [:button "Set"]]
             [:a {:href "/admin/forms"} "← Forms"]
             [:a {:href (str "/form/" id "/new") :target "_blank"} "New document ↗"]]
            [:div.ed-split
             [:div.ed-left
              [:div.ed-tabs
               [:button.ed-tab.active {:data-pane "edn"} "EDN"]
               [:button.ed-tab {:data-pane "css"} "CSS"]]
              [:div#edn-pane.ed-pane]
              [:div#css-pane.ed-pane {:hidden true}]]
             [:div.ed-right
              ;; shown only for multi-view forms (toggled from the preview headers)
              [:div.ed-preview-bar
               [:label {:for "preview-view"} "Preview page:"]
               [:select#preview-view]]
              [:iframe#preview.ed-preview]]]
            [:script {:type "application/json" :id "edn-init"} (h/raw (json/write-value-as-string edn))]
            [:script {:type "application/json" :id "css-init"} (h/raw (json/write-value-as-string css))]
            [:script {:type "module"} (h/raw editor-js)]))
          (resp/content-type "text/html")))))
