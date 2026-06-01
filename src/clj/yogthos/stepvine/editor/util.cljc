(ns yogthos.stepvine.editor.util
  ;; exclude unconditionally: clojure.core/random-uuid exists in both Clojure
  ;; (since 1.11) and ClojureScript, so shadowing it warns on the clj side too
  (:refer-clojure :exclude [random-uuid])
  (:require [domino.util :as u]))

(def random-uuid u/random-uuid)
