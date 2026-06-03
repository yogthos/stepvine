(ns yogthos.stepvine.endpoints
  "The HTTP endpoint URL scheme for field/collection operations.

   Widgets emit `@post('<url>')` Datastar intents pointing at these routes; the
   matching web handlers (`web.collection_*`, `web.editor`, the per-field POST
   routes) parse the same scheme. This namespace is the single source of truth
   for that contract — the renderer (`render.clj`) deliberately does NOT own
   routes; widgets and re-render code require this module directly.

   Item-awareness: a single-level collection item keeps the established
   `/coll/<coll>/<idx>/…` routes (no regression); a nested item (path depth > 1)
   uses the generic deep-path `/citem/…?path=coll/idx/coll/idx` endpoints.

   See `signals.clj` for the companion Datastar signal-name vocabulary, and
   ARCHITECTURE.md for the request flow."
  (:require
   [clojure.string :as str]
   [yogthos.stepvine.signals :as signals]))

(defn field-post-url
  "POST endpoint for a field change, item-aware. A single-level item keeps the
   established `/coll/<coll>/<idx>/field/<fid>` route (no regression); a nested
   item (path depth > 1) uses the generic deep-path endpoint
   `/citem/field/<fid>?path=coll/idx/coll/idx`."
  [ctx id]
  (let [path (signals/item-path ctx)]
    (cond
      (nil? path)        (str "/doc/" (:doc-id ctx) "/field/" (name id))
      (= 2 (count path)) (str "/doc/" (:doc-id ctx) "/coll/" (name (first path))
                              "/" (second path) "/field/" (name id))
      :else              (str "/doc/" (:doc-id ctx) "/citem/field/" (name id)
                              "?path=" (str/join "/" (map name path))))))

(defn coll-item-url
  "Endpoint for a collection-level op (add/remove) at `path` (`[coll idx …]`).
   Depth-1 keeps the established `/coll/<coll>[/<idx>]/<op>` route; deeper paths
   use the generic `/citem/<op>?path=…` endpoint."
  [ctx path op]
  (if (<= (count path) 2)
    (str "/doc/" (:doc-id ctx) "/coll/" (name (first path))
         (when (= 2 (count path)) (str "/" (second path))) "/" op)
    (str "/doc/" (:doc-id ctx) "/citem/" op "?path=" (str/join "/" (map name path)))))

(defn- with-op
  "Append `/op` to a field URL, before any query string (so nested
   `/citem/field/<fid>?path=…` becomes `/citem/field/<fid>/op?path=…`)."
  [url op]
  (let [[base q] (str/split url #"\?" 2)]
    (str base "/" op (when q (str "?" q)))))

(defn field-lock-url   [ctx id] (with-op (field-post-url ctx id) "lock"))
(defn field-unlock-url [ctx id] (with-op (field-post-url ctx id) "unlock"))
