(ns yogthos.stepvine.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[stepvine starting]=-"))
   :start      (fn []
                 (log/info "\n-=[stepvine started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[stepvine has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})
