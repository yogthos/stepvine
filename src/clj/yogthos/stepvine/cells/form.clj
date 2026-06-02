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
   [yogthos.stepvine.access :as access]
   [yogthos.stepvine.audit :as audit]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.documents :as documents]
   [yogthos.stepvine.effects :as effects]
   [yogthos.stepvine.users :as users]
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
  "Cascading dropdowns: re-render the dropdowns whose parent value moved in this
   transaction (`changed` = the engine's change-set) and patch each to peers. The
   VALUE cascade already happened inside Domino — synthesized clearing events
   (§cascades) cleared the whole chain in this transaction, those cleared signals
   broadcast like any change, and the change-set names every field that moved — so
   this re-renders exactly the affected <select>s (new option list + cleared
   selection), nothing more."
  [{:keys [session-manager hub options-store]} doc-id changed]
  (let [sess    (session/current session-manager doc-id)
        markup  (render/view-markup sess :default)
        aliases (get-in sess [:form :views :default :opts :widget-namespaces])
        deps    (render/dropdowns-depending-on markup aliases changed)]
    (when (seq deps)
      (let [ctx (-> (render/session->context sess :default doc-id)
                    (assoc :options (options/resolve-field-options
                                     options-store (render/all-field-opts sess))))]
        (doseq [{:keys [node]} deps]
          (hub/broadcast-elements! hub doc-id (render/render-view ctx node)))))))

(myc/defcell :form/apply-field
  {:requires [:forms :documents :session-manager :patient-client :audit :hub :options-store :users]
   :input    {:doc-id :any :field-id :any :uid :any :raw-value :any}
   :output   {:status :int :body :string}
   :doc      "Coerce + transact the change (lock-aware + finalized-doc-aware), then
              audit the diff, re-render dependent (cascading) dropdowns from the
              change-set, and perform the side-effect intents the engine emitted
              (email / notify / import)."}
  (fn [{:keys [session-manager patient-client documents audit users] :as resources}
       {:keys [doc-id field-id uid raw-value]}]
    (if-let [{:keys [form-raw]} (docs/ensure! resources doc-id)]
      (cond
        (documents/locked? (documents/get-document documents doc-id))
        {:status 409 :body "Document is read-only (finalized)."}   ; §15.5 enforcement

        ;; granular per-field permission: reject writes to a field this user's roles
        ;; don't permit (the read-only render is UX; this is the security boundary)
        (let [wroles (:write-roles (get-in (session/current session-manager doc-id) [:field-opts (keyword field-id)]))]
          (and (seq wroles) users (not (access/role-permitted? (users/get-user users uid) wroles))))
        {:status 403 :body "You don't have permission to edit this field."}

        ;; state-based editability: reject a write to a :writable-in field when the
        ;; document is not in one of those workflow states
        (let [win    (:writable-in (get-in (session/current session-manager doc-id) [:field-opts (keyword field-id)]))
              wstate (when (seq win)
                       (documents/workflow-state (documents/get-document documents doc-id)
                                                 (get-in form-raw [:workflow :initial])))]
          (and (seq win) (not (contains? (set win) wstate))))
        {:status 409 :body "This field can't be edited in the current state."}

        :else
        (let [sess   (session/current session-manager doc-id)
              fid    (keyword field-id)
              value  (coerce (get-in sess [:field-opts fid]) raw-value)
              before (session/value session-manager doc-id fid)]
          (session/apply-field-as! session-manager doc-id uid fid value)
          (let [after    (session/value session-manager doc-id fid)
                ;; the engine's change-set + the effect intents it emitted, both
                ;; from this transaction (the edit + whatever its events moved)
                changed  (session/changed-ids session-manager doc-id)
                emitted  (session/emitted-effects session-manager doc-id)]
            (when (not= before after)                              ; skip no-ops / lock-rejected
              (audit/record! audit {:doc-id doc-id :by uid :action :field/save
                                    :path [fid] :before before :after after})
              (rerender-dependents! resources doc-id changed)      ; re-render what changed (UI)
              (effects/perform-all! resources doc-id form-raw emitted))) ; email / notify / import
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
