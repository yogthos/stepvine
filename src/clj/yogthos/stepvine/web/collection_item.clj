(ns yogthos.stepvine.web.collection-item
  "Nested collection item edits (parity stepvine-jj9): generic deep-path endpoints
   for collections nested more than one level. The one-level routes
   (/coll/:coll/:idx/…) are untouched; these handle arbitrary depth via a `path`
   query param `coll/idx/coll/idx…`.

     POST /doc/:id/citem/field/:fid?path=teams/t1/members/m1   set a nested field
     POST /doc/:id/citem/field/:fid/(lock|unlock)?path=…       no-op (nested items
                                                               are not locked in v1)
     POST /doc/:id/citem/add?path=teams/t1/members             add a nested item
     POST /doc/:id/citem/remove?path=teams/t1/members/m1       remove a nested item

   After any change the ROOT collection (the first path segment) is re-rendered and
   broadcast, which redraws the whole nested tree."
  (:require
   [clojure.string :as str]
   [jsonista.core :as json]
   [starfederation.datastar.clojure.api :as d*]
   [yogthos.stepvine.cells.form :as cform]
   [yogthos.stepvine.docs :as docs]
   [yogthos.stepvine.hub :as hub]
   [yogthos.stepvine.options :as options]
   [yogthos.stepvine.render :as render]
   [yogthos.stepvine.session :as session]))

(defn- parse-path
  "`teams/t1/members/m1` -> `[:teams \"t1\" :members \"m1\"]` (collection segments
   are keywords, index segments stay strings)."
  [param]
  (->> (str/split (or param "") #"/")
       (remove str/blank?)
       (map-indexed (fn [i seg] (if (even? i) (keyword seg) seg)))
       vec))

(defn- leaf-field-opts
  "Walk the form model down the collection keys to a leaf field's opts (for type
   coercion). `coll-keys` is the path's collection keywords, e.g. [:teams :members]."
  [form coll-keys fid]
  (loop [model (get-in form [:data :model]) ks coll-keys]
    (if (seq ks)
      (let [entry (some #(when (= (first ks) (first %)) %) model)]
        (recur (get-in (second entry) [:schema :model]) (rest ks)))
      (let [entry (some #(when (= (keyword fid) (first %)) %) model)]
        (if (map? (second entry)) (second entry) {})))))

(defn- rerender-root! [{:keys [session-manager hub options-store]} doc-id root-coll]
  (let [sess (session/current session-manager doc-id)
        ctx  (-> (render/session->context sess :default doc-id)
                 (assoc :options (options/resolve-field-options
                                  options-store (render/all-field-opts sess))))]
    (when-let [html (render/render-collection ctx sess :default root-coll)]
      (hub/broadcast-elements! hub doc-id html))))

(defn handler
  "Build a deep-path collection handler for `op` (:field, :add, :remove, :noop)."
  [{:keys [session-manager] :as resources} op]
  (fn [req]
    (let [doc-id (get-in req [:path-params :id])
          path   (parse-path (get-in req [:query-params "path"]))]
      (when (and (docs/ensure! resources doc-id) (seq path))
        (case op
          :field  (let [fid     (get-in req [:path-params :fid])
                        signals (try (json/read-value (d*/get-signals req)) (catch Exception _ {}))
                        sig     (render/item-signal-name {:item {:path path}} (keyword fid))
                        form    (:form (session/current session-manager doc-id))
                        fopts   (leaf-field-opts form (vec (take-nth 2 path)) fid)]
                    (session/set-deep-item-field! session-manager doc-id path fid
                                                  (cform/coerce fopts (get signals sig))))
          :add    (session/add-deep-item! session-manager doc-id path)
          :remove (session/remove-deep-item! session-manager doc-id path)
          :noop   nil)
        ;; the root collection (first path segment) redraws the whole nested tree
        (when (not= op :noop)
          (rerender-root! resources doc-id (first path))))
      {:status 204 :body ""})))
