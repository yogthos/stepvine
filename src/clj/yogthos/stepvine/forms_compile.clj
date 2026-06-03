(ns yogthos.stepvine.forms-compile
  "Form compilation: turn a raw stored form into the form the engine serves.

   The store (`yogthos.stepvine.forms`) returns raw EDN and deliberately knows
   nothing about cascades/imports/validation/partials — those are *downstream*
   concerns. This namespace is the compile layer on top of it: `prepare-form`
   splices partials, compiles declarative validation/cascades into Domino
   events/reactions, and turns event-triggered imports into Domino effect
   signals. `get-form`/`get-form-version` are the prepared reads callers use
   (raw reads stay on the store as `forms/raw-form`)."
  (:require
   [yogthos.stepvine.cascades :as cascades]
   [yogthos.stepvine.forms :as forms]
   [yogthos.stepvine.imports :as imports]
   [yogthos.stepvine.partials :as partials]
   [yogthos.stepvine.validation :as validation]))

(defn- compile-import-effects
  "Add a form effect per event-triggered import, so an import is fired by the
   engine's effect signal (not the host inspecting changes)."
  [form]
  (let [effs (imports/->effects form)]
    (cond-> form
      (seq effs) (update-in [:data :effects] (fnil into []) effs))))

(defn prepare-form
  "Resolve a served form: splice partials (§15.9), compile declarative validation
   into error + :valid? reactions (§15.8), compile cascading-dropdown dependencies
   into Domino clearing events, and event-triggered imports into Domino effect
   signals. Public so the live editor can preview a form exactly as it will be
   served. `store` supplies the partials map via its `:partials`."
  [store form]
  (some->> form
           (partials/splice (:partials store))
           validation/compile-validations
           cascades/compile-cascades
           compile-import-effects))

(defn get-form
  "The current working form by id, with partials spliced + validation compiled.
   Used for previews/builder + new-document listing; loaded documents resolve
   their pinned version via `get-form-version`."
  [store id]
  (prepare-form store (forms/raw-form store id)))

(defn get-form-version
  "The exact archived form for a pinned `[id version]` (partials spliced), falling
   back to the working form when the archive has no such entry."
  [store id v]
  (prepare-form store (or (forms/-archived store (keyword id) v)
                          (forms/raw-form store id))))
