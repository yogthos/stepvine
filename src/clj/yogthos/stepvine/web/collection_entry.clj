(ns yogthos.stepvine.web.collection-entry
  "Modal data-entry commit (parity stepvine-ugx): POST /doc/:id/coll/:coll/add-from
   appends a NEW collection row built from scratch (temp) field signals, then
   clears those temp fields and closes the modal on the committing client.

   The :c/entry-modal widget posts here with two query params:
     fields=<item-field>:<temp-field>,…   item field <- temp field mapping
     modal=<signal>                        the modal's open signal (to close it)"
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.ring :as ds-ring]
   [yogthos.stepvine.cells.form :as cform]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]))

(defn- parse-fields
  "Parse `item:tmp,qty:newqty` into [[item-field-kw temp-field-kw temp-signal] …]."
  [param]
  (for [pair (str/split (or param "") #",")
        :let [[ifield tfield] (str/split pair #":")]
        :when (and (seq ifield) (seq tfield))]
    [(keyword ifield) (keyword tfield) (render/signal-name (keyword tfield))]))

(defn- rerender-collection!
  "Re-render the collection container and broadcast it to every viewer."
  [{:keys [session-manager hub options-store]} doc-id coll-id]
  (let [sess (session/current session-manager doc-id)
        ctx  (-> (render/session->context sess :default doc-id)
                 (assoc :options (options/resolve-field-options
                                  options-store (render/all-field-opts sess))))]
    (when-let [html (render/render-collection ctx sess :default coll-id)]
      (hub/broadcast-elements! hub doc-id html))))

(defn handler
  "Build the modal-entry commit handler closed over the document resources."
  [{:keys [session-manager] :as resources}]
  (fn [req]
    (let [doc-id  (get-in req [:path-params :id])
          coll    (keyword (get-in req [:path-params :coll]))
          modal   (not-empty (get-in req [:query-params "modal"]))
          pairs   (parse-fields (get-in req [:query-params "fields"]))
          signals (try (json/read-value (d*/get-signals req)) (catch Exception _ {}))]
      (when (docs/ensure! resources doc-id)
        (let [sess  (session/current session-manager doc-id)
              fopts (get-in (render/collections-data sess) [coll :field-opts])
              row   (into {} (for [[ifield _tfield tsig] pairs
                                   :let [v (cform/coerce (get fopts ifield) (get signals tsig))]
                                   :when (some? v)]
                               [ifield v]))]
          ;; commit the scratch values as one new row …
          (session/add-item-with! session-manager doc-id coll row)
          ;; … then reset the temp document fields so they can't leak into a
          ;; later submission (the broadcast clears the bound inputs for viewers)
          (session/apply-change! session-manager doc-id
                                 (vec (for [[_ tfield _] pairs] [tfield nil])))
          (rerender-collection! resources doc-id coll)))
      ;; close the modal + clear the scratch signals on the committing client
      (ds-ring/->sse-response
       req {ds-ring/on-open
            (fn [gen]
              (d*/patch-signals!
               gen (json/write-value-as-string
                    (into (if modal {modal false} {})
                          (map (fn [[_ _ tsig]] [tsig ""]) pairs)))))}))))
