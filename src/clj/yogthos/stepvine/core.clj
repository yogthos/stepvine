(ns yogthos.stepvine.core
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [yogthos.stepvine.config :as config]
   [yogthos.stepvine.env :refer [defaults]]

    ;; Edges
   [yogthos.stepvine.web.server]
   [yogthos.stepvine.web.handler]

    ;; Form-document engine
   [yogthos.stepvine.partials]
   [yogthos.stepvine.forms]
   [yogthos.stepvine.documents]
   [yogthos.stepvine.audit]
   [yogthos.stepvine.hub]
   [yogthos.stepvine.session]
   [yogthos.stepvine.options]
   [yogthos.stepvine.clients]
   [yogthos.stepvine.users]
   [yogthos.stepvine.access]

    ;; Routes
   [yogthos.stepvine.web.routes.api]
   [yogthos.stepvine.web.routes.pages])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (fn [thread ex]
   (log/error {:what :uncaught-exception
               :exception ex
               :where (str "Uncaught exception on" (.getName thread))})))

(defonce system (atom nil))

(defn stop-app []
  ((or (:stop defaults) (fn [])))
  (some-> (deref system) (ig/halt!)))

(defn start-app [& [params]]
  ((or (:start params) (:start defaults) (fn [])))
  (->> (config/system-config (or (:opts params) (:opts defaults) {}))
       (ig/expand)
       (ig/init)
       (reset! system)))

(defn -main [& _]
  (start-app)
  (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (stop-app) (shutdown-agents)))))
