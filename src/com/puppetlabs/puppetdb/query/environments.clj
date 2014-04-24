(ns com.puppetlabs.puppetdb.query.environments
  (:require [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :as query]))

(defn all-environments []
  (query/execute-query* 0 ["select name from environments"]))

(defn query-environment [environment]
  (-> (query/execute-query* 0 ["select name from environments where name=?" environment])
      (update-in [:result] first)))
