(ns puppetlabs.pe-puppetdb-extensions.config
  (:require [puppetlabs.puppetdb.config :as conf]))

(defn adjust-for-extensions [config]
  (-> config
      (update-in [:sync :allow-unsafe-sync-triggers]
                 #(or % false))))

(def config-service
  (conf/create-defaulted-config-service (comp adjust-for-extensions
                                              conf/process-config!)))
