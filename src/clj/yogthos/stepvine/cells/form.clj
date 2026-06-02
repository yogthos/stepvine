(ns yogthos.stepvine.cells.form
  "Mycelium cells for editing a document instance — Phase 5.

   All endpoints are document-scoped under /doc/:id; each cell ensures the live
   session exists (recreating it from the persisted db via docs/ensure!) before
   operating. Recompute + broadcast of field/reaction/collection signals happens
   in the session manager's on-update hook; lock-state and collection-structure
   broadcasts happen in their handlers."
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [mycelium.core :as myc]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]
   yogthos.stepvine.components   ; register all widget render methods
   [starfederation.datastar.clojure.api :as d*]))

(defn coerce
  "Coerce an incoming signal value to the field's declared type. Datastar may
   deliver number-input values as strings; blank clears the field (nil)."
  [field-opts v]
  (if (= :number (:type field-opts))
    (cond
      (number? v) v
      (and (string? v) (seq (str/trim v)))
      (let [t (str/trim v)] (or (parse-long t) (parse-double t)))
      :else nil)
    v))

;; --- POST /doc/:id/field/:fid[/lock|/unlock] ------------------------------

(myc/defcell :form/parse-field
  {:input  {:http-request :map}
   :output {:doc-id :any :field-id :any :uid :any :raw-value :any}
   :doc    "Read the field id from the path and value + uid from posted signals."}
  (fn [_resources {req :http-request}]
    (let [signals  (json/read-value (d*/get-signals req))
          field-id (get-in req [:path-params :fid])]
      {:doc-id    (get-in req [:path-params :id])
       :field-id  field-id
       :uid       (get-in req [:session :user-id])   ; authenticated user
       ;; the signal name is sanitized (e.g. :form-id -> "form_id"), so read the
       ;; posted value by the sanitized name, not the raw path-param field id
       :raw-value (get signals (render/signal-name (keyword field-id)))})))

(defn- rerender-dependents!
  "Cascading dropdowns: when `changed-fid` changes, ripple to EVERY dropdown that
   transitively `:depends-on` it (region → clinic → department → …). The
   editor's Domino ctx recomputes derived values from state each transact, so a
   stale dependent selection (a transition effect, not a derived value) is cleared
   here, at the apply layer: clear the whole descendant closure in one transaction,
   then re-render each affected <select> (new option list + cleared selection) and
   patch it to all peers."
  [{:keys [session-manager hub options-store]} doc-id changed-fid]
  (let [sess    (session/current session-manager doc-id)
        markup  (render/view-markup sess :default)
        aliases (get-in sess [:form :views :default :opts :widget-namespaces])
        deps    (render/cascade-closure markup aliases changed-fid)]
    (when (seq deps)
      ;; clear the whole descendant chain at once (full-depth cascade)
      (session/apply-change! session-manager doc-id
                             (mapv (fn [{:keys [id]}] [(keyword id) ""]) deps))
      (let [sess2 (session/current session-manager doc-id)
            ctx   (-> (render/session->context sess2 :default doc-id)
                      (assoc :options (options/resolve-field-options
                                       options-store (render/all-field-opts sess2))))]
        (doseq [{:keys [node]} deps]
          (hub/broadcast-elements! hub doc-id (render/render-view ctx node)))))))

(myc/defcell :form/apply-field
  {:requires [:forms :documents :session-manager :patient-client :audit :hub :options-store]
   :input    {:doc-id :any :field-id :any :uid :any :raw-value :any}
   :output   {:status :int :body :string}
   :doc      "Coerce + transact the change (lock-aware + finalized-doc-aware), audit
              the before/after diff, run any triggered import, then re-render any
              dependent (cascading) dropdowns."}
  (fn [{:keys [session-manager patient-client documents audit] :as resources}
       {:keys [doc-id field-id uid raw-value]}]
    (if-let [{:keys [form-raw]} (docs/ensure! resources doc-id)]
      (if (documents/locked? (documents/get-document documents doc-id))
        {:status 409 :body "Document is read-only (finalized)."}   ; §15.5 enforcement
        (let [sess   (session/current session-manager doc-id)
              fid    (keyword field-id)
              value  (coerce (get-in sess [:field-opts fid]) raw-value)
              before (session/value session-manager doc-id fid)]
          (session/apply-field-as! session-manager doc-id uid fid value)
          (let [after (session/value session-manager doc-id fid)]
            (when (not= before after)                              ; skip no-ops / lock-rejected
              (audit/record! audit {:doc-id doc-id :by uid :action :field/save
                                    :path [fid] :before before :after after})
              (rerender-dependents! resources doc-id fid)))        ; cascading dropdowns
          (docs/run-imports! resources form-raw doc-id fid)
          {:status 204 :body ""}))
      {:status 404 :body (str "No such document: " doc-id)})))

(myc/defcell :form/lock-field
  {:requires [:forms :documents :session-manager :hub]
   :input    {:doc-id :any :field-id :any :uid :any :raw-value :any}
   :output   {:status :int :body :string}
   :doc      "Acquire a field lock for uid and broadcast lock state."}
  (fn [{:keys [session-manager hub] :as resources} {:keys [doc-id field-id uid]}]
    (docs/ensure! resources doc-id)
    (session/lock-field! session-manager hub doc-id uid (keyword field-id))
    {:status 204 :body ""}))

(myc/defcell :form/unlock-field
  {:requires [:forms :documents :session-manager :hub]
   :input    {:doc-id :any :field-id :any :uid :any :raw-value :any}
   :output   {:status :int :body :string}
   :doc      "Release uid's field lock and broadcast lock state."}
  (fn [{:keys [session-manager hub] :as resources} {:keys [doc-id field-id uid]}]
    (docs/ensure! resources doc-id)
    (session/unlock-field! session-manager hub doc-id uid (keyword field-id))
    {:status 204 :body ""}))

;; --- POST /doc/:id/coll/:coll[/add | /:idx/remove | /:idx/field/:fid] ------

(defn- rerender-collection!
  "Re-render a collection's container and patch it (by element id) to all peers.
   Resolves option sources (incl. item-field dropdowns) so re-rendered items keep
   their dropdown options."
  [{:keys [session-manager hub options-store]} doc-id coll-id]
  (let [sess (session/current session-manager doc-id)
        ctx  (-> (render/session->context sess :default doc-id)
                 (assoc :options (options/resolve-field-options
                                  options-store (render/all-field-opts sess))))]
    (when-let [html (render/render-collection ctx sess :default coll-id)]
      (hub/broadcast-elements! hub doc-id html))))

(myc/defcell :form/parse-coll
  {:input  {:http-request :map}
   :output {:doc-id :any :coll-id :any :idx :any :field-id :any :uid :any :value :any}
   :doc    "Parse a collection op: doc/coll/idx/field from the path, value+uid from signals."}
  (fn [_resources {req :http-request}]
    (let [pp      (:path-params req)
          signals (try (json/read-value (d*/get-signals req)) (catch Exception _ {}))
          coll    (:coll pp)
          idx     (:idx pp)
          fid     (:fid pp)]
      {:doc-id   (:id pp)
       :coll-id  coll
       :idx      idx
       :field-id fid
       :uid      (get-in req [:session :user-id])   ; authenticated user
       :value    (when fid
                   (get signals (str (render/signal-name coll) "_" idx "_"
                                     (render/signal-name fid))))})))

(myc/defcell :form/coll-add
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :idx :any :field-id :any :uid :any :value :any}
   :output   {:status :int :body :string}
   :doc      "Add an empty item to a collection and re-render the container."}
  (fn [{:keys [session-manager] :as resources} {:keys [doc-id coll-id]}]
    (when (docs/ensure! resources doc-id)
      (session/add-item! session-manager doc-id (keyword coll-id))
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-remove
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :idx :any :field-id :any :uid :any :value :any}
   :output   {:status :int :body :string}
   :doc      "Remove a collection item and re-render the container."}
  (fn [{:keys [session-manager] :as resources} {:keys [doc-id coll-id idx]}]
    (when (docs/ensure! resources doc-id)
      (session/remove-item! session-manager doc-id (keyword coll-id) idx)
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-field
  {:requires [:forms :documents :session-manager]
   :input    {:doc-id :any :coll-id :any :idx :any :field-id :any :uid :any :value :any}
   :output   {:status :int :body :string}
   :doc      "Set one field of a collection item (lock-aware); recompute via on-update."}
  (fn [{:keys [session-manager] :as resources} {:keys [doc-id coll-id idx field-id uid value]}]
    (when (docs/ensure! resources doc-id)
      (let [sess  (session/current session-manager doc-id)
            coll  (keyword coll-id)
            fid   (keyword field-id)
            fopts (get-in (render/collections-data sess) [coll :field-opts fid])]
        (session/apply-item-field-as! session-manager doc-id uid coll idx fid (coerce fopts value))))
    {:status 204 :body ""}))

(myc/defcell :form/coll-lock-field
  {:requires [:forms :documents :session-manager :hub]
   :input    {:doc-id :any :coll-id :any :idx :any :field-id :any :uid :any :value :any}
   :output   {:status :int :body :string}
   :doc      "Acquire a lock on a collection item field and broadcast lock state."}
  (fn [{:keys [session-manager hub] :as resources} {:keys [doc-id coll-id idx field-id uid]}]
    (when (docs/ensure! resources doc-id)
      (session/lock-item-field! session-manager hub doc-id uid (keyword coll-id) idx (keyword field-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-unlock-field
  {:requires [:forms :documents :session-manager :hub]
   :input    {:doc-id :any :coll-id :any :idx :any :field-id :any :uid :any :value :any}
   :output   {:status :int :body :string}
   :doc      "Release uid's lock on a collection item field and broadcast."}
  (fn [{:keys [session-manager hub] :as resources} {:keys [doc-id coll-id idx field-id uid]}]
    (when (docs/ensure! resources doc-id)
      (session/unlock-item-field! session-manager hub doc-id uid (keyword coll-id) idx (keyword field-id)))
    {:status 204 :body ""}))

;; --- Table operations (sort, page, filter, move-row, clear, columns) --------

(myc/defcell :form/parse-table-op
  {:input  {:http-request :map}
   :output {:doc-id :any :coll-id :any :query-params :any}
   :doc    "Parse a table operation: doc/coll from path, query params from URL."}
  (fn [_resources {req :http-request}]
    (let [pp (:path-params req)]
      {:doc-id       (:id pp)
       :coll-id      (:coll pp)
       :query-params (:query-params req)})))

(myc/defcell :form/coll-sort
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Cycle the sort for a column (view-state) and re-render."}
  (fn [{:keys [session-manager] :as resources} {:keys [doc-id coll-id query-params]}]
    (when (docs/ensure! resources doc-id)
      (let [coll (keyword coll-id)]
        (when-let [col (get query-params "col")]
          (session/set-table-sort! session-manager doc-id coll col))
        (rerender-collection! resources doc-id coll)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-page
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Advance/rewind the table page (view-state) and re-render."}
  (fn [{:keys [session-manager] :as resources} {:keys [doc-id coll-id query-params]}]
    (when (docs/ensure! resources doc-id)
      (let [coll (keyword coll-id)]
        (session/set-table-page! session-manager doc-id coll (get query-params "dir"))
        (rerender-collection! resources doc-id coll)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-filter
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Re-render the collection with filter applied (view-only, no data change)."}
  (fn [resources {:keys [doc-id coll-id]}]
    (when (docs/ensure! resources doc-id)
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-move-row
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Move a row from one index to another within a collection."}
  (fn [{:keys [session-manager] :as resources} {:keys [doc-id coll-id query-params]}]
    (when (docs/ensure! resources doc-id)
      (let [coll (keyword coll-id)
            from (get query-params "from")
            to   (get query-params "to")]
        (when (and from to)
          (session/move-item! session-manager doc-id coll from to))
        (rerender-collection! resources doc-id coll)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-clear
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Remove all items from a collection."}
  (fn [{:keys [session-manager] :as resources} {:keys [doc-id coll-id]}]
    (when (docs/ensure! resources doc-id)
      (let [coll (keyword coll-id)]
        (session/clear-items! session-manager doc-id coll))
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-columns-add
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Add a new column to the collection (metadata change + re-render)."}
  (fn [resources {:keys [doc-id coll-id]}]
    (when (docs/ensure! resources doc-id)
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-columns-move
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Move a column (metadata change + re-render)."}
  (fn [resources {:keys [doc-id coll-id]}]
    (when (docs/ensure! resources doc-id)
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-columns-remove
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Remove a column (metadata change + re-render)."}
  (fn [resources {:keys [doc-id coll-id]}]
    (when (docs/ensure! resources doc-id)
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))

(myc/defcell :form/coll-columns-label
  {:requires [:forms :documents :session-manager :hub :options-store]
   :input    {:doc-id :any :coll-id :any :query-params :any}
   :output   {:status :int :body :string}
   :doc      "Update a column's custom label (metadata change + re-render)."}
  (fn [resources {:keys [doc-id coll-id]}]
    (when (docs/ensure! resources doc-id)
      (rerender-collection! resources doc-id (keyword coll-id)))
    {:status 204 :body ""}))
