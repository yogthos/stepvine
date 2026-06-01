(ns yogthos.stepvine.versions
  "Immutable form-version archive (PLAN.md §15.1).

   Every published form version is frozen here under `[form-id version]` with a
   content `digest`, so a document can pin and reload the *exact* version it was
   created against even after the authoring form is edited (version pinning,
   §15.2). The authoring copy in `:store/forms` is the mutable working form; this
   archive is the immutable history.

   Immutability rule (dev-friendly + correct): the highest version for a form is
   the *working* copy and may be re-published with new content; any *superseded*
   version (one with a higher version present) is **sealed** — re-publishing it
   with different content throws. Bump `:version` to evolve a published form.

   Backed by a duratom when a file is configured (survives restarts so pins stay
   resolvable), or a plain atom for tests. Keyed by `[id version]`."
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [duratom.core :as duratom])
  (:import
   [java.security MessageDigest]))

;; --- Content digest -------------------------------------------------------

(defn- canonical
  "Deterministic representation for hashing: maps become key-sorted, sets sorted,
   sequences keep order. Independent of hash-map key ordering across runs."
  [x]
  (cond
    (map? x)        (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                          (map (fn [[k v]] [k (canonical v)]) x))
    (set? x)        (into (sorted-set-by #(compare (pr-str %1) (pr-str %2)))
                          (map canonical x))
    (sequential? x) (mapv canonical x)
    :else           x))

(defn digest
  "Stable SHA-256 hex of a form's content, independent of map key order."
  [form]
  (let [bytes (.getBytes (pr-str (canonical form)) "UTF-8")
        md    (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md bytes)
         (map #(format "%02x" %))
         (apply str))))

;; --- Archive store --------------------------------------------------------

(defn archive
  "A fresh in-memory archive (for tests)."
  []
  (atom {}))

(defn- entry [a id v] (get @a [id v]))

(defn list-versions
  "All archived version numbers for a form, ascending."
  [a id]
  (->> @a keys (keep (fn [[fid v]] (when (= fid id) v))) sort vec))

(defn latest-version
  "Highest published (non-draft) version number for a form, or nil."
  [a id]
  (->> @a
       (keep (fn [[[fid v] e]] (when (and (= fid id) (not (:draft? (:form e)))) v)))
       (reduce max ##-Inf)
       (#(when (not= ##-Inf %) %))))

(defn get-version
  "The exact archived form for `[id v]`, or nil."
  [a id v]
  (:form (entry a id v)))

(defn- sealed?
  "A version is sealed (immutable) once a higher version exists for the form."
  [a id v]
  (boolean (some (fn [[[fid vv] _]] (and (= fid id) (> vv v))) @a)))

(defn publish!
  "Freeze `form` at its declared `:version` (default 1). No-op if an identical
   digest is already archived there. Overwrites the working (highest) version on
   content change, but throws if a *sealed* (superseded) version would change.
   Returns `{:id :version :digest}`."
  [a form]
  (let [id (:id form)
        v  (:version form 1)
        d  (digest form)
        ex (entry a id v)]
    (cond
      (and ex (= d (:digest ex)))
      nil                                              ; identical — nothing to do

      (and ex (sealed? a id v))
      (throw (ex-info "Refusing to mutate a sealed form version; bump :version"
                      {:id id :version v :archived-digest (:digest ex) :new-digest d}))

      :else
      (swap! a assoc [id v] {:form form :digest d
                             :published-at (System/currentTimeMillis)}))
    {:id id :version v :digest d}))

(defn publish-all!
  "Publish a seq of authoring forms into the archive (startup). Logs and skips a
   form that violates immutability rather than failing the whole boot."
  [a forms]
  (doseq [form forms]
    (try (publish! a form)
         (catch clojure.lang.ExceptionInfo e
           (log/warn "skipping form version publish:" (ex-message e) (ex-data e))))))

(defn init-archive
  "An archive backed by a duratom when `file` is given (survives restarts so pins
   stay resolvable), else a plain in-memory atom."
  [file]
  (if file
    (do (io/make-parents file)
        (log/info "form-version archive persisted to" file)
        (duratom/duratom :local-file :file-path file :init {}))
    (archive)))
