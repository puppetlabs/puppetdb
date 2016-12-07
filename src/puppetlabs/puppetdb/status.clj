(ns puppetlabs.puppetdb.status
  (:require [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.query.summary-stats :as summary-stats]
            [schema.core :as s]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [trptcolin.versioneer.core :as versioneer]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.kitchensink.core :as ks]))

(def status-details-schema {:maintenance_mode? s/Bool
                            :queue_depth (s/maybe s/Int)
                            :read_db_up? s/Bool
                            :write_db_up? s/Bool
                            :node_count (s/maybe s/Int)
                            :catalog_count (s/maybe s/Int)
                            :report_count (s/maybe s/Int)
                            :factset_count (s/maybe s/Int)})

;; This is vendored from the tk-status-service because version checking fails
;; semver validation on PDB snapshots. When we address this upstream we can put
;; the tk version back in.
(defn get-artifact-version
  [group-id artifact-id]
  (let [version (versioneer/get-version group-id artifact-id)]
    (when (empty? version)
      (throw (IllegalStateException.
              (trs
               "Unable to find version number for ''{0}/{1}''"
               group-id
               artifact-id))))
    version))

(pls/defn-validated status-details :- status-details-schema
  "Returns a map containing status information on the various parts of
  a running PuppetDB system. This data can be interpreted to determine
  whether the system is considered up"
  [config :- {s/Any s/Any}
   shared-globals-fn :- pls/Function
   maint-mode-fn? :- pls/Function]
  (let [{:keys [scf-read-db scf-write-db]} (shared-globals-fn)
        read-db-up? (sutils/db-up? scf-read-db)
        simple-size-stats (when read-db-up? (->> (summary-stats/collect-simple-size-stats scf-read-db)
                                                 (ks/mapvals #(-> % first :count))))]
    {:maintenance_mode? (maint-mode-fn?)
     :queue_depth (utils/nil-on-failure (mq/queue-size))
     :read_db_up? read-db-up?
     :write_db_up? (sutils/db-up? scf-write-db)
     :node_count (-> simple-size-stats :node_count)
     :catalog_count (-> simple-size-stats :catalog_count)
     :report_count (-> simple-size-stats :report_count)
     :factset_count (-> simple-size-stats :factset_count)}))

(pls/defn-validated create-status-map :- status-core/StatusCallbackResponse
  "Returns a status map containing the state of the currently running
  system (starting/running/error etc)"
  [{:keys [maintenance_mode? read_db_up? write_db_up?]
    :as status-details} :- status-details-schema]
  (let [state (cond
                maintenance_mode? :starting
                (and read_db_up? write_db_up?) :running
                :else :error)]
    {:state state
     :status status-details}))

(defn register-pdb-status
  "Registers the PuppetDB instance in TK status using `register-fn`
  with the associated `status-callback-fn`"
  [register-fn status-callback-fn]
  (register-fn "puppetdb-status"
               (get-artifact-version "puppetlabs" "puppetdb")
               1
               status-callback-fn))
