(ns yogthos.stepvine.env
  (:require
    [clojure.tools.logging :as log]
    [yogthos.stepvine.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[stepvine starting using the development or test profile]=-"))
   :start      (fn []
                 (log/info "\n-=[stepvine started successfully using the development or test profile]=-"))
   :stop       (fn []
                 (log/info "\n-=[stepvine has shut down successfully]=-"))
   :middleware wrap-dev
   :opts       {:profile       :dev}})
