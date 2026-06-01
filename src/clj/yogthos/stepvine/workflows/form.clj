(ns yogthos.stepvine.workflows.form
  "Mycelium workflows for editing a document — field updates, locking,
   collection ops (the document page/render lives in workflows.document)."
  (:require
   [mycelium.core :as myc]
   ;; load cell definitions
   [yogthos.stepvine.cells.form]))

(def update-field-def
  {:cells    {:start :form/parse-field
              :apply :form/apply-field}
   :pipeline [:start :apply]})

(def update-field (myc/pre-compile update-field-def))

(def lock-field-def
  {:cells    {:start :form/parse-field
              :lock  :form/lock-field}
   :pipeline [:start :lock]})

(def lock-field (myc/pre-compile lock-field-def))

(def unlock-field-def
  {:cells    {:start  :form/parse-field
              :unlock :form/unlock-field}
   :pipeline [:start :unlock]})

(def unlock-field (myc/pre-compile unlock-field-def))

;; --- Collections ----------------------------------------------------------

(def coll-add
  (myc/pre-compile {:cells    {:start :form/parse-coll :add :form/coll-add}
                    :pipeline [:start :add]}))

(def coll-remove
  (myc/pre-compile {:cells    {:start :form/parse-coll :remove :form/coll-remove}
                    :pipeline [:start :remove]}))

(def coll-field
  (myc/pre-compile {:cells    {:start :form/parse-coll :field :form/coll-field}
                    :pipeline [:start :field]}))

(def coll-lock
  (myc/pre-compile {:cells    {:start :form/parse-coll :lock :form/coll-lock-field}
                    :pipeline [:start :lock]}))

(def coll-unlock
  (myc/pre-compile {:cells    {:start :form/parse-coll :unlock :form/coll-unlock-field}
                    :pipeline [:start :unlock]}))
