(ns yogthos.stepvine.workflows.document
  "Mycelium workflows for the document lifecycle — Phase 5."
  (:require
   [mycelium.core :as myc]
   ;; load cell definitions
   [yogthos.stepvine.cells.document]))

(def index
  (myc/pre-compile {:cells {:start :index/render} :pipeline [:start]}))

(def create
  (myc/pre-compile {:cells    {:start :doc/parse-create :create :doc/create}
                    :pipeline [:start :create]}))

(def render-doc
  (myc/pre-compile {:cells    {:start :doc/parse :render :doc/render}
                    :pipeline [:start :render]}))

(def run-export
  (myc/pre-compile {:cells    {:start :doc/parse-action :export :doc/run-export}
                    :pipeline [:start :export]}))

(def share
  (myc/pre-compile {:cells    {:start :doc/parse-owner-op :share :doc/share}
                    :pipeline [:start :share]}))

(def delete
  (myc/pre-compile {:cells    {:start :doc/parse-owner-op :delete :doc/delete}
                    :pipeline [:start :delete]}))

(def build
  (myc/pre-compile {:cells    {:start :doc/parse :build :doc/build}
                    :pipeline [:start :build]}))

(def submit
  (myc/pre-compile {:cells    {:start :doc/parse-view-op :submit :doc/submit}
                    :pipeline [:start :submit]}))

(def revise
  (myc/pre-compile {:cells    {:start :doc/parse-view-op :revise :doc/revise}
                    :pipeline [:start :revise]}))
