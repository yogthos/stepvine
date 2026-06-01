(ns yogthos.stepvine.components.widgets.selection.array-signal
  "Shared helpers for widgets bound to an :array field signal (multi-select,
   selection-list, tree-select). Field changes broadcast signals only — no
   element re-render — so selected state must be expressed as reactive Datastar
   bindings over the array signal, not as server-only classes.")

(defn arr-expr
  "A null-safe reference to the array signal, e.g. `(($skills)||[])`."
  [sig]
  (str "(($" sig ")||[])"))

(defn includes-expr
  "Datastar boolean expression: is value `v` currently in the array signal?"
  [sig v]
  (str (arr-expr sig) ".includes('" v "')"))

(defn toggle-expr
  "Datastar click/change expression: toggle `v` in the array signal, then POST."
  [sig v url]
  (str "$" sig " = " (includes-expr sig v)
       " ? ($" sig ").filter(x => x !== '" v "')"
       " : " (arr-expr sig) ".concat('" v "');"
       " @post('" url "')"))

(defn add-expr
  "Datastar click expression: add `v` to the array signal (idempotent), then POST."
  [sig v url]
  (str "if(!" (includes-expr sig v) "){$" sig " = " (arr-expr sig) ".concat('" v "');} @post('" url "')"))

(defn remove-expr
  "Datastar click expression: remove `v` from the array signal, then POST."
  [sig v url]
  (str "$" sig " = ($" sig ").filter(x => x !== '" v "'); @post('" url "')"))
