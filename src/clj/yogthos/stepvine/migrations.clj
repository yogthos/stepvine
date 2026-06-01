(ns yogthos.stepvine.migrations
  "Form revisions + document migrations.

   A form may carry a `:version` (integer, default 1) and an optional
   `:migrations` map `{from-version (fn [db] db')}`: each entry upgrades a
   document's Domino db from `from-version` to `from-version + 1`. When a document
   created against an older form version is loaded, its db is run through the
   intervening migrations, re-initialized against the current schema (so derived
   fields recompute), persisted, and its version bumped.

   Migration fns live in the form EDN as quoted lists (like events/reactions) and
   are evaluated safely via sci (yogthos.stepvine.editor.impl/eval-form)."
  (:require
   [yogthos.stepvine.editor.impl :as impl]))

(defn current-version
  "The form's declared version (default 1)."
  [form-raw]
  (:version form-raw 1))

(defn migrate
  "Bring `db` from `from-version` up to the form's current version by applying the
   form's `:migrations` transforms in order. Returns the migrated db."
  [form-raw from-version db]
  (let [form       (impl/eval-form form-raw)   ; realizes the quoted migration fns
        to-version (current-version form)
        migrations (:migrations form)]
    (reduce (fn [db v]
              (if-let [f (get migrations v)]
                (f db)
                db))
            db
            (range from-version to-version))))

(defn needs-migration?
  [form-raw doc-version]
  (< (or doc-version 1) (current-version form-raw)))
