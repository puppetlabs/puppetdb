(ns puppetlabs.puppetdb.status
  (:require [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [trptcolin.versioneer.core :as versioneer]
            [puppetlabs.i18n.core :refer [trs]]))

(def status-schema (assoc
                    status-core/StatusCallbackResponse
                    :status
                    (s/maybe
                     {:maintenance_mode? s/Bool
                      :queue_depth (s/maybe s/Int)
                      :read_db_up? s/Bool
                      :write_db_up? s/Bool
                      :write_dbs_up? s/Bool
                      :write_db {s/Str {:up? s/Bool}}})))

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

(s/defn create-status-map :- status-schema
  "Returns a status map containing the state of the currently running
  system (starting/running/error etc)"
  [level :- s/Keyword
   shared-globals-fn :- pls/Function
   get-maint-mode :- pls/Function
   get-shutdown-reason :- pls/Function]
  (if (= :critical level)
    {:state (cond
             (get-shutdown-reason) :stopping
             (get-maint-mode) :starting
             :else :running)
     :status nil}
    ;; If level is not :critical (:info or :debug)
    (let [maint-mode? (get-maint-mode)
          {:keys [scf-write-dbs scf-write-db-names scf-read-db]} (shared-globals-fn)
          write-db-statuses (map sutils/db-up? scf-write-dbs)
          read-db-up? (sutils/db-up? scf-read-db)
          write-db-up? (boolean (some identity write-db-statuses))]
      {:state (cond
               (get-shutdown-reason) :stopping
               maint-mode? :starting
               (and read-db-up? write-db-up?) :running
               :else :error)
       :status {:maintenance_mode? maint-mode?
                :queue_depth (utils/nil-on-failure (mq/queue-size))
                ;; Is the read-db up?
                :read_db_up? read-db-up?
                ;; Is at least one write-db up?
                :write_db_up? write-db-up?
                ;; Are all write-db's up?
                :write_dbs_up? (every? identity write-db-statuses)
                ;; What is the individual status of each write-db?
                :write_db (zipmap scf-write-db-names
                                  (map #(hash-map :up? %) write-db-statuses))}})))

