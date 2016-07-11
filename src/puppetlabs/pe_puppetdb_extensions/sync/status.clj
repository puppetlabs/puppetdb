(ns puppetlabs.pe-puppetdb-extensions.sync.status
  (:require [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]))

(def state-schema
  (s/enum :idle :syncing :error))

(def entity-schema
  (s/enum :reports :catalogs :factsets :nodes))

(def phase-schema
  (s/enum :summary :transfer))

(def sync-status-schema
  {:state state-schema
   (s/optional-key :entity-status) {entity-schema
                                    {:phase phase-schema
                                     (s/optional-key :processed) s/Int
                                     (s/optional-key :total) s/Int}}
   (s/optional-key :error-message) s/Str})

(def update-message-schema
  {:entity entity-schema
   :phase phase-schema
   (s/optional-key :total) s/Int})

(def initial
  {:state :idle})

;; Status updating

(pls/defn-validated reset
  [current-status :- sync-status-schema
   state :- state-schema]
  :- sync-status-schema
  {:state state})

(pls/defn-validated update-for-command
  [current-status :- sync-status-schema command] :- sync-status-schema
  (let [entity (case command
                 "store report" :reports
                 "replace catalog" :catalogs
                 "replace facts" :factsets
                 "deactivate node" :nodes)]
    (update-in current-status
               [:entity-status entity :processed] #(if % (inc %) 1))))

(pls/defn-validated update-for-status-message
  [current-status :- sync-status-schema
   {:keys [entity phase total]} :- update-message-schema]
  :- sync-status-schema
  (update-in current-status [:entity-status entity]
             merge {:phase phase} (when total {:total total})))

(pls/defn-validated update-for-error
  [current-status :- sync-status-schema message] :- sync-status-schema
  (assoc current-status
         :state :error
         :error-message message))

;;; Formatting

(def readable-entity-name
  {:reports "reports"
   :catalogs "catalogs"
   :factsets "facts"
   :nodes "node deactivations"})

(defn format-entity-status [entity phase processed total]
  (let [entity-name (readable-entity-name entity)]
   (case phase
     :summary (format "Reconciling %s" entity-name)
     :transfer (if processed
                 (format "Transferring %s (%d/%d)" entity-name processed total)
                 (format "Transferring %d %s" total entity-name)))))

(pls/defn-validated alerts
  [{:keys [state entity-status error-message]} :- sync-status-schema]

  (case state
    :idle []
    :error [{:severity :warning
             :message error-message}]
    :syncing (for [[entity {:keys [phase processed total] :or {total 0}}] entity-status
                   :when (or (= phase :summary)
                             (and (= phase :transfer)
                                  (pos? total)
                                  (< (or processed 0) total)))]
               {:severity :info
                :message (format-entity-status entity phase processed total)})))
