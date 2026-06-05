(ns yogthos.stepvine.routes-test
  "The route trees must assemble into a reitit router. Regression guard: the page
   init once returned a bare vector-of-routes instead of a single route node,
   which crashed the router build at startup (and 500'd matched requests). A unit
   build catches a malformed tree (bad shape, route conflicts) without a server."
  (:require
   [clojure.test :refer [deftest is]]
   [integrant.core :as ig]
   [reitit.ring :as ring]
   yogthos.stepvine.web.routes.api
   yogthos.stepvine.web.routes.pages))

(defn- stub-opts
  "Minimal opts for the page routes — the handlers are created but never invoked
   during a router build, so stub stores are fine."
  []
  {:forms (atom {}) :documents (atom {}) :session (atom {}) :hub (atom {})
   :options-store {} :patient-client nil :users (atom {}) :audit (atom [])
   :reports-dir "data/reports" :oauth {:providers []} :access (atom {})
   :mailer nil :http-client nil})

(deftest router-assembles
  (let [pages ((ig/init-key :reitit.routes/pages (stub-opts)))
        api   ((ig/init-key :reitit.routes/api {}))]
    ;; mirrors web.handler's :router/core: (ring/router ["" opts routes])
    (is (some? (ring/router ["" {} [api pages]]))
        "the combined api + pages route tree builds into a reitit router")))
