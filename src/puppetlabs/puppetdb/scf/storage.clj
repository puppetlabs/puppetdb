(ns puppetlabs.puppetdb.scf.storage
  "Catalog persistence

  Catalogs are persisted in a relational database. Roughly speaking,
  the schema looks like this:

  * resource_parameters are associated 0 to N catalog_resources (they are
  deduped across catalogs). It's possible for a resource_param to exist in the
  database, yet not be associated with a catalog. This is done as a
  performance optimization.

  * edges are associated with a single catalog

  * catalogs are associated with a single certname

  * facts are associated with a single certname

   The standard set of operations on information in the database will
   likely result in dangling resources and catalogs; to clean these
   up, it's important to run `garbage-collect!`."
  (:require
   [clojure.data :as data]
   [clojure.java.jdbc :as sql]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [honey.sql :as hsql]
   [metrics.counters :refer [counter inc! value]]
   [metrics.gauges :refer [gauge-fn]]
   [metrics.histograms :refer [histogram update!]]
   [metrics.timers :refer [timer time!]]
   [murphy :refer [try!]]
   [next.jdbc :as nxt]
   [next.jdbc.plan :refer [select-one!]]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.puppetdb.catalogs :as cat]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.facts :as facts :refer [facts-schema]]
   [puppetlabs.puppetdb.jdbc :as jdbc :refer [do-hsql query-to-vec]]
   [puppetlabs.puppetdb.metrics.core :as metrics]
   [puppetlabs.puppetdb.package-util :as pkg-util]
   [puppetlabs.puppetdb.reports :as reports]
   [puppetlabs.puppetdb.scf.hash :as shash]
   [puppetlabs.puppetdb.scf.partitioning :as partitioning]
   [puppetlabs.puppetdb.scf.storage-utils :as sutils
    :refer [munge-hash-for-storage]]
   [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
   [puppetlabs.puppetdb.time :as time
    :refer [ago now to-timestamp from-sql-date before?]]
   [puppetlabs.puppetdb.utils :as utils
    :refer [env-config-for-db-ulong with-noisy-failure]]
   [puppetlabs.puppetdb.utils.metrics :as mutils]
   [schema.core :as s])
  (:import
   (clojure.lang ExceptionInfo)
   (java.security MessageDigest)
   (java.sql SQLException Timestamp)
   (java.time ZoneId ZonedDateTime)
   (java.util Arrays)
   (org.joda.time Period)
   (org.postgresql.util PGobject)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def resource-ref-schema
  {:type String
   :title String})

(def json-primitive-schema (s/cond-pre String Number Boolean))

(def resource-schema
  (merge resource-ref-schema
         {(s/optional-key :exported) Boolean
          (s/optional-key :file) String
          (s/optional-key :line) s/Int
          (s/optional-key :tags) #{String}
          (s/optional-key :aliases) #{String}
          (s/optional-key :parameters) {s/Any s/Any}}))

(def resource-ref->resource-schema
  {resource-ref-schema resource-schema})

(def edge-relationship-schema (s/enum :contains :before :required-by :notifies :subscription-of))

(def edge-schema
  {:source resource-ref-schema
   :target resource-ref-schema
   :relationship edge-relationship-schema})

(def catalog-schema
  "This is a bit of a hack to make a more restrictive schema in the storage layer.
  Moving the more restrictive resource/edge schemas into puppetdb.catalogs is TODO. Upstream
  code needs to assume a map of resources (not a vector) and tests need to be update to adhere
  to the new format."
  (assoc cat/catalog-wireformat-schema
         :resources resource-ref->resource-schema
         :edges #{edge-schema}))

(def environments-schema
  {:id s/Int
   :environment s/Str})

(def processing-status-schema
  {:status (s/enum :duplicate :incorporated :stale :obsolete)
   (s/optional-key :hash) (s/cond-pre s/Str (s/pred bytes?))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas - Internal

(def resource-ref->hash {resource-ref-schema String})

(def edge-db-schema
  #{[(s/one String "source hash")
     (s/one String "target hash")
     (s/one String "relationship type")]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Metrics

(def storage-metrics-registry (get-in metrics/metrics-registries [:storage :registry]))

;; ## Storage metrics
;;
;; ### Timers for catalog storage
;;
;; * `:replace-catalog`: the time it takes to replace the catalog for
;;   a host
;;
;; * `:new-catalog`: the time it takes to persist a catalog for a
;;    never before seen certname
;;
;; * `:catalog-hash-match`: the time it takes to persist the updates
;;    for a catalog with a matches the previously stored hash
;;
;; * `:catalog-hash-miss`: the time it takes to persist the
;;    differential updates of the previously stored catalog and this one
;;
;; * `:add-resources`: the time it takes to persist just a catalog's
;;   resources
;;
;; * `:add-edges`: the time it takes to persist just a catalog's edges
;;
;; * `:catalog-hash`: the time it takes to compute a catalog's
;;   similary hash
;;
;; ### Counters for catalog storage
;;
;; * `:updated-catalog`: how many brand new or updated (non-duplicate)
;;    catalogs we've received
;;
;; * `:duplicate-catalog`: how many duplicate catalogs we've received
;;
;; ### Gauges for catalog storage
;;
;; * `:duplicate-pct`: percentage of incoming catalogs determined to
;;   be duplicates
;;
;; ### Catalog Histograms
;;
;; * `:catalog-volatility`: number of inserts/updates/deletes required
;;   per hash miss
;;
;; ### Timers for garbage collection
;;
;; * `:gc`: the time it takes to collect all database garbage
;;
;; * `:gc-catalogs`: the time it takes to remove all unused catalogs
;;
;; * `:gc-params`: the time it takes to remove all unused resource params
;;
;; ### Timers for fact storage
;;
;; * `:replace-facts`: the time it takes to replace the facts for a
;;   host
;;
;; * `:new-fact`: the time it takes to persist facts for a
;;    never before seen certname
;;
(def storage-metrics (atom {}))

(defn get-storage-metric
  ([metric]
   (get-storage-metric metric nil))
  ([metric db]
   ;; some metrics are updated outside of where *db* is bound
   ;; the db map is passed directly in those cases
   (let [prefixed-key (-> (or db jdbc/*db*)
                          (mutils/get-db-name)
                          (mutils/maybe-prefix-key metric))]
     (prefixed-key @storage-metrics))))

(defn create-storage-metrics [prefix]
  (let [pname #(or (when prefix (str prefix "." %)) %)
        storage-metrics
        {:add-resources      (timer storage-metrics-registry [(pname "add-resources")])
         :add-edges          (timer storage-metrics-registry [(pname "add-edges")])
         :resource-hashes    (timer storage-metrics-registry [(pname "resource-hashes")])
         :catalog-hash       (timer storage-metrics-registry [(pname "catalog-hash")])
         :add-new-catalog    (timer storage-metrics-registry [(pname "new-catalog-time")])
         :add-new-fact       (timer storage-metrics-registry [(pname "new-fact-time")])
         :catalog-hash-match (timer storage-metrics-registry [(pname "catalog-hash-match-time")])
         :catalog-hash-miss  (timer storage-metrics-registry [(pname "catalog-hash-miss-time")])
         :replace-catalog    (timer storage-metrics-registry [(pname "replace-catalog-time")])
         :gc                 (timer storage-metrics-registry [(pname "gc-time")])
         :gc-catalogs        (timer storage-metrics-registry [(pname "gc-catalogs-time")])
         :gc-params          (timer storage-metrics-registry [(pname "gc-params-time")])
         :gc-environments    (timer storage-metrics-registry [(pname "gc-environments-time")])
         :gc-packages    (timer storage-metrics-registry [(pname "gc-packages-time")])
         :gc-report-statuses (timer storage-metrics-registry [(pname "gc-report-statuses")])
         :gc-fact-paths  (timer storage-metrics-registry [(pname "gc-fact-paths")])
         :updated-catalog    (counter storage-metrics-registry [(pname "new-catalogs")])
         :duplicate-catalog  (counter storage-metrics-registry [(pname "duplicate-catalogs")])
         :duplicate-pct      (gauge-fn storage-metrics-registry [(pname "duplicate-pct")]
                                       (fn []
                                         (let [dupes (value ((mutils/maybe-prefix-key prefix :duplicate-catalog)
                                                             @storage-metrics))
                                               new   (value ((mutils/maybe-prefix-key prefix :updated-catalog)
                                                             @storage-metrics))]
                                           (float (kitchensink/quotient dupes (+ dupes new))))))
         :catalog-volatility (histogram storage-metrics-registry [(pname "catalog-volitilty")])
         :replace-facts     (timer storage-metrics-registry [(pname "replace-facts-time")])
         :store-report      (timer storage-metrics-registry [(pname "store-report-time")])}]
    (mutils/prefix-metric-keys prefix storage-metrics)))

(defn init-storage-metrics [scf-write-dbs]
  (->> scf-write-dbs
       (map mutils/get-db-name)
       (map create-storage-metrics)
       (apply merge)
       (reset! storage-metrics)))


(def command-sql-statement-timeout-ms
  (env-config-for-db-ulong "PDB_COMMAND_SQL_STATEMENT_TIMEOUT_MS"
                           (* 10 60 1000)))

(def table-lock-order
  "This provides a partial ordering of tables, to ensure that we always lock
  them in a consistent order."
  ["certnames" "reports" "resource_events"])

(defn acquire-locks!
  "Acquires a lock on the specified table."
  [table-modes]
  {:pre [(every? string? (keys table-modes))
         (every? string? (vals table-modes))]}
  (let [ordered-tables (sort-by #(.indexOf table-lock-order %) (keys table-modes))]
    (apply jdbc/do-commands (map #(str "LOCK TABLE " % " IN " (get table-modes %) " MODE") ordered-tables))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Certname querying/deleting

(pls/defn-validated add-certname!
  "Add the given host to the db"
  [certname :- String]
  (let [r (jdbc/insert! :certnames {:certname certname})]
    (jdbc/insert! :certnames_status {:certname certname})
    r))

(defn add-certnames
  "Inserts the collection of certnames."
  [certnames]
  (let [c (jdbc/connection)]
    (doseq [batch (partition-all 1000 (map vector certnames))]
      (->> {:insert-into :certnames :columns [:certname] :values batch}
           hsql/format (nxt/execute! c))
      (->> {:insert-into :certnames_status :columns [:certname] :values batch}
           hsql/format (nxt/execute! c)))))

(pls/defn-validated ensure-certname
  "Adds the given host to the db iff it isn't already there."
  [certname :- String]
  (let [c (jdbc/connection)]
    (->> {:insert-into :certnames :columns [:certname] :values [[[:inline certname]]]
          :on-conflict [] :do-nothing true}
         hsql/format (nxt/execute! c))
    (->> {:insert-into :certnames-status :values [[[:inline certname]]]
          :on-conflict [] :do-nothing true}
         hsql/format (nxt/execute! c))))

(defn delete-certname!
  "Delete the given host from the db"
  [certname]
  {:pre [certname]}
  ;; With partitioning, we must execute this delete on every active partition
  (doseq [table (partitioning/get-partition-names "resource_events")]
    (jdbc/delete! table ["certname_id in (select id from certnames where certname=?)" certname]))
  (doseq [table (partitioning/get-partition-names "reports")]
    (jdbc/delete! table ["certname=?" certname]))
  (jdbc/delete! :catalog_inputs ["certname_id in (select id from certnames where certname=?)" certname])
  (jdbc/delete! :certname_packages ["certname_id in (select id from certnames where certname=?)" certname])
  (jdbc/delete! :certnames ["certname=?" certname]))

(defn delete-certnames!
  "Delete the list of hosts from the db"
  [certnames]
  (let [select-certname-ids {:select [:id] :from [:certnames] :where [:in :certname certnames]}]
    ;; With partitioning, we must execute this delete on every active partition
    (doseq [table (partitioning/get-partition-names "resource_events")]
      (do-hsql {:delete-from (keyword table)
                :where [:in :certname-id select-certname-ids]}))
    (doseq [table (partitioning/get-partition-names "reports")]
      (do-hsql {:delete-from (keyword table)
                :where [:in :certname certnames]}))
    (do-hsql {:delete-from :catalog-inputs
              :where [:in :certname-id select-certname-ids]})
    (do-hsql {:delete-from :certname-packages
              :where [:in :certname-id select-certname-ids]})
    (do-hsql {:delete-from :certnames
              :where [:in :certname certnames]})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Node activation/deactivation

(defn node-deactivated-time
  "Returns the time the node specified by `certname` was deactivated, or nil if
  the node is currently active."
  [certname]
  {:pre [(string? certname)]}
  (jdbc/query-with-resultset
   ["SELECT deactivated FROM certnames_status WHERE certname=?" certname]
   (comp :deactivated first sql/result-set-seq)))

(defn node-expired-time
  "Returns the time the node specified by `certname` expired, or nil if
  the node is currently active."
  [certname]
  {:pre [(string? certname)]}
  (jdbc/query-with-resultset
   ["SELECT expired FROM certnames_status WHERE certname=?" certname]
   (comp :expired first sql/result-set-seq)))

(defn purge-deactivated-and-expired-nodes!
  "Delete nodes from the database which were deactivated before horizon."
  ([horizon]
   (purge-deactivated-and-expired-nodes! horizon nil))
  ([horizon batch-limit]
   (let [ts (to-timestamp horizon)]
     (when-not ts
       (throw (Exception. (trs "Provided horizon not a valid timestamp {0}"
                               (pr-str horizon)))))
     (let [certnames-to-delete
           (-> {:select [:certname]
                :from :certnames_status
                :where [:or
                        [:< :deactivated ts]
                        [:< :expired ts]]}
               (cond-> batch-limit (assoc :limit batch-limit))
               hsql/format
               jdbc/query
               (->> (map :certname)))]
       (when (seq certnames-to-delete)
         (delete-certnames! certnames-to-delete))))))

(pls/defn-validated set-certname-facts-expiration :- processing-status-schema
  [certname :- s/Str
   expire? :- s/Bool
   updated :- pls/Timestamp]
  (let [updated (to-timestamp updated)]
    (jdbc/do-prepared
     (str "insert into certname_fact_expiration as cfe (certid, expire, updated)"
          "  select id, ?, ? from certnames where certname = ?"
          "  on conflict (certid) do update"
          "    set expire = excluded.expire,"
          "        updated = excluded.updated"
          "    where excluded.updated > cfe.updated")
     [expire? updated certname])
    {:status :incorporated}))

(defn named-row-id
  "Returns the id (primary key) from the table for the row with col = name."
  [table col name]
  (->> {:select :id :from table :where [:= col name]}
       hsql/format (select-one! (jdbc/connection)  :id)))

(defn environment-id [name] (named-row-id :environments :environment name))
(defn certname-id [name] (named-row-id :certnames :certname name))
(defn producer-id [name] (named-row-id :producers :name name))
(defn status-id [name] (named-row-id :report_statuses :status name))

(defn ensure-named-row
  "Returns the id (primary key) of the named row, creating it if necessary."
  [table col name]
  (when name
    (or (named-row-id table col name)
        (->> {:insert-into table :columns [col] :values [[name]] :returning [:id]}
             hsql/format (select-one! (jdbc/connection) :id)))))

(defn ensure-environment [name] (ensure-named-row :environments :environment name))
(defn ensure-producer [name] (ensure-named-row :producers :name name))
(defn ensure-status [name] (ensure-named-row :report_statuses :status name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog updates/changes

(defn latest-catalog-metadata
  "Returns the id, hash, certname_id and producer_timestamp of certname's
  catalog."
  [certname]
  {:pre [certname]}
  (jdbc/query-with-resultset
   [(format "select catalogs.id as catalog_id, certnames.id as certname_id,
             %s as catalog_hash, catalogs.producer_timestamp
             from certnames
             left join catalogs on catalogs.certname=certnames.certname
             where certnames.certname=?"
            (sutils/sql-hash-as-str "catalogs.hash"))
    certname]
   (comp first sql/result-set-seq)))

(defn munge-edges-for-storage [edges]
  (->> edges
       (map (fn [{:keys [source target relationship]}]
              {:source_type (:type source)
               :source_title (:title source)
               :target_type (:type target)
               :target_title (:title target)
               :relationship relationship}))
       sutils/munge-jsonb-for-storage))

(defn munge-resources-for-storage [resources]
  (->> resources
       (map (partial merge {:file nil :line nil}))
       sutils/munge-jsonb-for-storage))

(s/defn catalog-row-map
  "Creates a row map for the catalogs table, optionally adding envrionment when it was found"
  [hash
   {:keys [version
           code_id
           job_id
           transaction_uuid
           catalog_uuid
           environment
           producer_timestamp
           producer]} :- catalog-schema
   received-timestamp :- pls/Timestamp]
  {:hash (sutils/munge-hash-for-storage hash)
   :catalog_version  version
   :transaction_uuid (sutils/munge-uuid-for-storage transaction_uuid)
   :catalog_uuid (sutils/munge-uuid-for-storage catalog_uuid)
   :timestamp (to-timestamp received-timestamp)
   :code_id code_id
   :job_id job_id
   :environment_id (ensure-environment environment)
   :producer_timestamp (to-timestamp producer_timestamp)
   :producer_id (ensure-producer producer)
   :api_version 1})

(s/defn update-catalog-metadata!
  "Given some catalog metadata, update the db"
  [id :- Long
   hash :- String
   catalog :- catalog-schema
   received-timestamp :- pls/Timestamp]
  (jdbc/update! :catalogs
                (catalog-row-map hash catalog received-timestamp)
                ["id=?" id]))

(defn add-catalog-metadata!
  "Stores catalog metadata for certname."
  [hash {:keys [certname] :as catalog} received-timestamp]
  (->> {:insert-into :catalogs
        :values [(assoc (catalog-row-map hash catalog received-timestamp)
                        :certname certname)]}
       hsql/format (nxt/execute-one! (jdbc/connection)))
  nil)

(s/defn resources-exist? :- #{String}
  "Given a collection of resource-hashes, return the subset that
   already exist in the database."
  [resource-hashes :- #{String}]
  (if (seq resource-hashes)
    (let [resource-array (->> (vec resource-hashes)
                              (map sutils/munge-hash-for-storage)
                              (sutils/array-to-param "bytea" org.postgresql.util.PGobject))
          query (format "SELECT DISTINCT %s as resource FROM resource_params_cache WHERE resource=ANY(?)"
                        (sutils/sql-hash-as-str "resource"))
          sql-params [query resource-array]]
      (jdbc/query-with-resultset sql-params
                                 #(set (map :resource (sql/result-set-seq %)))))
    #{}))

;;The schema definition of this function should be
;;resource-ref->resource-schema, but there are a lot of tests that
;;have incorrect data. When examples.clj and tests get fixed, this
;;should be changed to the correct schema
(s/defn catalog-resources
  "Returns the resource hashes keyed by resource reference"
  [certname-id :- Long]
  (jdbc/query-with-resultset
   [(format "SELECT type, title, tags, exported, file, line, %s
               AS resource
               FROM catalog_resources
               WHERE certname_id = ?"
            (sutils/sql-hash-as-str "resource")) certname-id]
   (fn [rs]
     (let [rss (sql/result-set-seq rs)]
       (zipmap (map #(select-keys % [:type :title]) rss)
               (for [rowmap rss]
                 (kitchensink/mapvals #(jdbc/convert-any-sql-array % set)
                                      rowmap)))))))

(s/defn new-params-only
  "Returns a map of not persisted parameters, keyed by hash"
  [persisted-params :- #{String}
   refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- resource-ref->hash]
  (reduce-kv (fn [acc resource-ref {:keys [parameters]}]
               (let [resource-hash (get refs-to-hashes resource-ref)]
                 (if (contains? persisted-params (refs-to-hashes resource-ref))
                   acc
                   (assoc acc resource-hash parameters))))
             {} refs-to-resources))

;; Test support
(def ^:dynamic *note-add-params-insert* identity)

(s/defn add-params!
  "Persists the new parameters found in `refs-to-resources` and populates the
   resource_params_cache."
  [refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- resource-ref->hash]
  (let [new-params (new-params-only (resources-exist? (kitchensink/valset refs-to-hashes))
                                    refs-to-resources
                                    refs-to-hashes)]

    (update! (get-storage-metric :catalog-volatility) (* 2 (count new-params)))

    (doseq [batch (partition-all 1000 new-params)]
      (->> {:insert-into :resource_params_cache
            :columns [:resource :parameters]
            :values (for [[resource-hash params] batch]
                      [(sutils/munge-hash-for-storage resource-hash)
                       (some-> params sutils/munge-jsonb-for-storage)])}
           *note-add-params-insert* hsql/format (nxt/execute! (jdbc/connection))))

    (doseq [batch (partition-all 1000 new-params)]
      ;; The batch might only have one item with params {}.
      (when-let [rows (seq (for [[resource-hash params] batch
                                 [k v] params]
                             [(sutils/munge-hash-for-storage resource-hash)
                              (name k)
                              (sutils/db-serialize v)]))]
        (->> {:insert-into :resource_params
              :columns [:resource :name :value]
              :values rows}
             *note-add-params-insert* hsql/format (nxt/execute! (jdbc/connection)))))))

(def resource-ref?
  "Returns true of the map is a resource reference"
  (every-pred :type :title))

(defn handle-resource-insert-sql-ex
  "Handles a SQLException encountered while inserting a resource. This
  may occur when the inserted resource has a value too big for a
  postgres btree index."
  [^SQLException ex certname row-or-rows]
  {:pre [(or (map? row-or-rows) (some-> (first row-or-rows) map?))]}

  (when-not (= (jdbc/sql-state :program-limit-exceeded) (.getSQLState ex))
    (throw ex))
  (when-not (utils/leaf-error? ex)
    (throw ex))

  ;; postgres 15.6: "index row requires ... bytes, maximum size is 8191"
  (letfn [(row-size [row]
            ;; Must cover all the relevant indexes
            (+ (-> row :type (.getBytes "UTF-8") alength)
               (-> row :title (.getBytes "UTF-8") alength)))
          (elide [s] (if (< (count s) 16) s (subs s 0 (min 15 (count s)))))
          ;; Might want to sort anyway, but sort for tests
          (key-rank [k] (.indexOf [:file :line :type :title] k))
          (locus-map [& args] (apply sorted-map-by #(compare (key-rank %1) (key-rank %2)) args))
          (res-msg [{:keys [file line type title]}]
            (if (and file line)
              (pr-str (locus-map :file file :line line))
              (pr-str (cond-> (locus-map)
                        file (assoc :file file)
                        line (assoc :line line)
                        type (assoc :type (elide type))
                        title (assoc :title (elide title))))))
          (msg [rows]
            (case (bounded-count 2 rows)
              0 (trs "A catalog resource for certname {0} is too large" (pr-str certname))
              1 (trs "A catalog resource for certname {0} is too large: {1}"
                     (pr-str certname) (-> rows first res-msg))
              (apply str
                     (trs "A catalog resource for certname {0} is too large; suspects:"
                          (pr-str certname))
                     (map #(str " " (res-msg %)) (sort-by #(- (row-size %)) rows)))))]
    (let [data {:kind ::resource-insert-limit-exceeded
                :puppetlabs.puppetdb/known-error? true}]
      (cond
        (map? row-or-rows) (throw (ex-info (msg [row-or-rows]) data))
        (not (second row-or-rows)) (throw (ex-info (msg row-or-rows) data))
        :else (let [large (filter #(> (row-size %) 8000) row-or-rows)]
                (throw (ex-info (msg large) data)))))))

(def log-catalog-resource-changes?
  (->> (or (System/getenv "PDB_LOG_CATALOG_RESOURCE_CHANGES") "no")
       (re-matches #"yes|true|1")
       seq))

;; Test support
(def ^:dynamic *note-insert-catalog-resources-insert* identity)

(s/defn insert-catalog-resources!
  [certname-id :- Long
   certname :- String
   refs-to-hashes :- {resource-ref-schema String}
   refs-to-resources :- resource-ref->resource-schema
   refs-to-insert]
  (update! (get-storage-metric :catalog-volatility) (count refs-to-insert))
  (when (and log-catalog-resource-changes?
             (seq refs-to-insert))
    (log/info (trs "Inserting new resources for certname {0}: {1}"
                   certname
                   (select-keys refs-to-hashes refs-to-insert))))
  (doseq [batch (partition-all 1000 refs-to-insert)]
    (let [rows (mapv (fn [resource-ref]
                       (let [{:keys [type title exported tags file line]}
                             (get refs-to-resources resource-ref)]
                         {:certname_id certname-id
                          :resource (-> resource-ref refs-to-hashes munge-hash-for-storage)
                          :type type
                          :title title
                          :tags (sutils/to-jdbc-varchar-array tags)
                          :exported exported
                          :file file
                          :line line}))
                     batch)
          sql (-> {:insert-into :catalog_resources :values rows}
                  *note-insert-catalog-resources-insert* hsql/format)]
      (try
        (nxt/execute! (jdbc/connection) sql)
        (catch SQLException ex
          (handle-resource-insert-sql-ex ex certname rows))))))

(s/defn delete-catalog-resources!
  [certname-id :- Long
   certname :- String
   old-resources
   refs-to-delete]
  (update! (get-storage-metric :catalog-volatility) (count refs-to-delete))

  (when (and log-catalog-resource-changes?
             (seq refs-to-delete))
    (log/info (trs "Deleting resources for certname {0}: {1}"
                   certname
                   (pr-str (map #(select-keys % [:type :title :resource])
                                (vals (select-keys old-resources refs-to-delete)))))))
  (doseq [{:keys [type title]} refs-to-delete]
    (jdbc/delete! :catalog_resources
                  ["certname_id = ? and type = ? and title = ?"
                   certname-id type title])))

(s/defn basic-diff
  "Basic diffing that returns only the keys/values of `right` whose values don't match those of `left`.
   This is different from clojure.data/diff in that it treats non-equal sets as completely different
   (rather than returning only the differing items of the set) and only returns differences from `right`."
  [left right]
  (reduce-kv (fn [acc k right-value]
               (let [left-value (get left k)]
                 (if (= left-value right-value)
                   acc
                   (assoc acc k right-value))))
             {} right))

(s/defn diff-resources-metadata
  "Return resource references with values that are only the key/values that from `right` that
   are different from those of the `left`. The keys/values here are suitable for issuing update
   statements that will update resources to the correct (new) values."
  [left right certname]
  (reduce-kv (fn [acc k right-values]
               (let [updated-resource-vals (basic-diff (get left k) right-values)]
                 (if (seq updated-resource-vals)
                   (do
                     (when log-catalog-resource-changes?
                       (log/info (trs "Certname {0} changed ''{1}'' resource ''{2}'' previously {3} now {4}"
                                      certname (:type k) (:title k)
                                      (select-keys (get left k)
                                                   (keys updated-resource-vals))
                                      updated-resource-vals)))
                     (assoc acc k updated-resource-vals))
                   acc))) {} right))

(defn merge-resource-hash
  "Assoc each hash from `refs-to-hashes` as :resource on `refs-to-resources`"
  [refs-to-hashes refs-to-resources]
  (reduce-kv (fn [acc k _]
               (assoc-in acc [k :resource] (get refs-to-hashes k)))
             refs-to-resources refs-to-resources))

(s/defn update-catalog-resources!
  [certname-id :- Long
   certname :- String
   refs-to-hashes :- {resource-ref-schema String}
   refs-to-resources
   old-resources
   maybe-updated-refs]
  (let [new-resources-with-hash (merge-resource-hash refs-to-hashes (select-keys refs-to-resources maybe-updated-refs))
        updated-resources (->> (diff-resources-metadata old-resources new-resources-with-hash certname)
                               (kitchensink/mapvals #(utils/update-when % [:resource] sutils/munge-hash-for-storage)))]

    (update! (get-storage-metric :catalog-volatility) (count updated-resources))

    (doseq [[{:keys [type title]} updates] updated-resources]
      ;; This should never throw a :program-limit-exceeded exception
      ;; because the type and title are the only values that could
      ;; overflow an index, and if they've changed we'll be
      ;; deleting/inserting, not updating.  So omit the related
      ;; catch.
      (jdbc/update! :catalog_resources
                    (if (contains? updates :tags)
                      (update updates :tags sutils/to-jdbc-varchar-array)
                      updates)
                    ["certname_id = ? and type = ? and title = ?"
                     certname-id type title]))))

(defn strip-params
  "Remove params from the resource as it is stored (and hashed) separately
  from the resource metadata"
  [resource]
  (dissoc resource :parameters))

(s/defn add-resources!
  "Persist the given resource and associate it with the given catalog."
  [certname-id :- Long
   certname :- String
   refs-to-resources :- resource-ref->resource-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (let [old-resources (catalog-resources certname-id)
        diffable-resources (kitchensink/mapvals strip-params refs-to-resources)]
    (jdbc/with-db-transaction []
      (add-params! refs-to-resources refs-to-hashes)
      (let [[deletes inserts updates] (data/diff (-> old-resources keys set)
                                                 (-> diffable-resources keys set))]
        (delete-catalog-resources! certname-id certname old-resources (or deletes #{}))
        (insert-catalog-resources! certname-id certname refs-to-hashes
                                   diffable-resources (or inserts #{}))
        (update-catalog-resources! certname-id certname refs-to-hashes
                                   diffable-resources old-resources
                                   (or updates #{}))))))

(s/defn catalog-edges-map
  "Return all edges for a given catalog id as a map"
  [certname :- String]
  ;; Transform the result-set into a map with [source,target,type] as the key
  ;; and nil as always the value. This just feeds into clojure.data/diff
  ;; better this way.
  (jdbc/query-with-resultset
   [(format "SELECT %s AS source, %s AS target, type FROM edges
               WHERE certname=?"
            (sutils/sql-hash-as-str "source")
            (sutils/sql-hash-as-str "target"))
    certname]
   #(zipmap (map vals (sql/result-set-seq %))
            (repeat nil))))

(s/defn delete-edges!
  "Delete edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, e.g.:

    [[<source> <target> <type>] ...]"
  [certname :- String
   edges :- edge-db-schema]

  (update! (get-storage-metric :catalog-volatility) (count edges))
  (doseq [[source target type] edges]
    ;; This is relatively inefficient. If we have id's for edges, we could do
    ;; this in 1 statement.
    (jdbc/delete! :edges
                  [(str "certname=?"
                        " and source=?::bytea"
                        " and target=?::bytea"
                        " and type=?")
                   certname
                   (sutils/bytea-escape source)
                   (sutils/bytea-escape target)
                   type])))

;; Test support
(def ^:dynamic *note-insert-edges-insert* identity)

(s/defn insert-edges!
  "Insert edges for a given certname.

  Edges must be either nil or a collection of lists containing each element
  of an edge, eg:

    [[<source> <target> <type>] ...]"
  [certname :- String
   edges :- edge-db-schema]
  (let [counts (for [batch (partition-all 1000 edges)]
                 (let [rows (mapv (fn [[source target type]]
                                    {:certname certname
                                     :source (sutils/munge-hash-for-storage source)
                                     :target (sutils/munge-hash-for-storage target)
                                     :type type})
                                  batch)]
                   (->> {:insert-into :edges :values rows}
                        *note-insert-edges-insert* hsql/format
                        (nxt/execute! (jdbc/connection))
                        first ::nxt/update-count)))]
    (update! (get-storage-metric :catalog-volatility) (reduce + counts))
    nil))

(s/defn replace-edges!
  "Persist the given edges in the database

  Each edge is looked up in the supplied resources map to find a
  resource object that corresponds to the edge. We then use that
  resource's hash for persistence purposes.

  For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
  then we'll lookup a resource with that key and use its hash."
  [certname :- String
   edges :- #{edge-schema}
   refs-to-hashes :- {resource-ref-schema String}]
  (let [new-edges (zipmap
                   (for [{:keys [source target relationship]} edges
                         :let [source-hash (refs-to-hashes source)
                               target-hash (refs-to-hashes target)
                               type        (name relationship)]]
                     [source-hash target-hash type])
                   (repeat nil))
        edge-keys (-> certname catalog-edges-map keys set)
        [inserts deletes _] (data/diff (-> new-edges keys set) edge-keys)]
    (insert-edges! certname (or inserts #{}))
    (delete-edges! certname (or deletes #{}))))

(s/defn update-existing-catalog
  "When a new incoming catalog has the same hash as an existing catalog, update
   storage-metrics and the transaction id for the new catalog"
  [catalog-id :- Long
   hash :- String
   catalog :- catalog-schema
   received-timestamp :- pls/Timestamp]
  (inc! (get-storage-metric :duplicate-catalog))
  (time! (get-storage-metric :catalog-hash-match)
         (update-catalog-metadata! catalog-id hash catalog received-timestamp)))

(s/defn update-catalog-associations!
  "Adds/updates/deletes the edges and resources for the given certname"
  [certname-id :- Long
   {:keys [resources edges certname]} :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}]
  (time! (get-storage-metric :add-resources)
         (add-resources! certname-id certname resources refs-to-hashes))
  (time! (get-storage-metric :add-edges)
         (replace-edges! certname edges refs-to-hashes)))

(s/defn replace-existing-catalog
  "New catalogs for a given certname needs to have their metadata, resources and
  edges updated."
  [certname-id :- Long
   catalog-id :- Long
   hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   received-timestamp :- pls/Timestamp]

  (inc! (get-storage-metric :updated-catalog))

  (time! (get-storage-metric :catalog-hash-miss)
         (update-catalog-metadata! catalog-id hash catalog received-timestamp)
         (update-catalog-associations! certname-id catalog refs-to-hashes)))

(s/defn add-new-catalog
  "Creates new catalog metadata and adds the proper associations for the edges and resources"
  [certname-id :- Long
   hash :- String
   catalog :- catalog-schema
   refs-to-hashes :- {resource-ref-schema String}
   received-timestamp :- pls/Timestamp]
  (inc! (get-storage-metric :updated-catalog))
  (time! (get-storage-metric :add-new-catalog)
         (do
           (add-catalog-metadata! hash catalog received-timestamp)
           (update-catalog-associations! certname-id catalog refs-to-hashes))))

(s/defn replace-catalog! :- processing-status-schema
  "Persist the supplied catalog in the database, returning its
   similarity hash."
  ([catalog :- catalog-schema]
   (replace-catalog! catalog (now)))
  ([{:keys [producer_timestamp resources certname] :as catalog} :- catalog-schema
    received-timestamp :- pls/Timestamp]
   (time! (get-storage-metric :replace-catalog)
          (jdbc/with-db-transaction []
            (let [{catalog-id :catalog_id
                   stored-hash :catalog_hash
                   certname-id :certname_id
                   latest-producer-timestamp :producer_timestamp} (latest-catalog-metadata certname)]
              (if (some-> latest-producer-timestamp
                          (.after (to-timestamp producer_timestamp)))
                {:status :stale}
                (let [hash (time! (get-storage-metric :catalog-hash)
                                  (shash/catalog-similarity-hash catalog))]
                  (if (= stored-hash hash)
                    (update-existing-catalog catalog-id hash catalog received-timestamp)
                    (let [refs-to-hashes (time! (get-storage-metric :resource-hashes)
                                                (kitchensink/mapvals shash/resource-identity-hash
                                                                     resources))]
                      (if (nil? catalog-id)
                        (add-new-catalog certname-id hash catalog refs-to-hashes received-timestamp)
                        (replace-existing-catalog certname-id catalog-id hash catalog
                                                  refs-to-hashes received-timestamp))))
                  {:status :incorporated :hash hash})))))))

(defn catalog-hash-for-certname
  "Returns the hash for the `certname` catalog"
  [certname]
  (jdbc/query-with-resultset
   ["SELECT %s as catalog FROM catalogs WHERE certname=?"
    (sutils/sql-hash-as-str "hash") certname]
   (comp :catalog first sql/result-set-seq)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog inputs updates/changes

(defn delete-catalog-inputs! [certid]
  (jdbc/delete! :catalog_inputs ["certname_id = ?" certid]))

(defn store-catalog-inputs!
  [certid inputs]
  (doseq [batch (partition-all 1000 inputs)]
    (->> {:insert-into :catalog_inputs
          :columns [:certname_id :type :name]
          :values (sequence (comp (map #(apply vector certid %)) (distinct))
                            batch)}
         hsql/format (nxt/execute! (jdbc/connection)))))

(defn update-catalog-input-metadata!
  [certid catalog-uuid last-updated inputs-hash]
  (jdbc/update! :certnames
                {:catalog_inputs_uuid catalog-uuid
                 :catalog_inputs_timestamp last-updated
                 :catalog_inputs_hash inputs-hash}
                ["id = ?" certid]))

(defn catalog-inputs-metadata [certname]
  (-> (jdbc/query (hsql/format
                    {:select [:id :catalog_inputs_timestamp :catalog_inputs_hash]
                     :from [:certnames]
                     :where [:= :certname certname]}))
      first))

(defn catalog-inputs-hash
  [^String certname inputs]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (.update digest (.getBytes certname "UTF-8"))
    (.update digest (byte 0))
    (doseq [[^String type ^String name] inputs]
      (.update digest (.getBytes type "UTF-8"))
      (.update digest (byte 0))
      (.update digest (.getBytes name "UTF-8"))
      (.update digest (byte 0)))
    (.digest digest)))

(pls/defn-validated replace-catalog-inputs! :- processing-status-schema
  [certname :- s/Str
   catalog_uuid :- s/Str
   inputs :- [[s/Str]]
   updated :- pls/Timestamp]
  (jdbc/with-db-transaction []
    (let [updated (to-timestamp updated)
          catalog-uuid (sutils/munge-uuid-for-storage catalog_uuid)
          {certid :id
           old-ts :catalog_inputs_timestamp
           ^bytes old-hash :catalog_inputs_hash} (catalog-inputs-metadata certname)]
      (if (and old-ts (not (before? (from-sql-date old-ts)
                                    (from-sql-date updated))))
        {:status :stale}
        (let [inputs (apply sorted-set inputs)
              ^bytes new-hash (catalog-inputs-hash certname inputs)]
          (when (not (Arrays/equals old-hash new-hash))
            (delete-catalog-inputs! certid)
            (store-catalog-inputs! certid inputs))
          (update-catalog-input-metadata! certid catalog-uuid updated new-hash)
          {:status :incorporated :hash new-hash})))))

;; ## Database compaction

(defn delete-unassociated-params!
  "Remove any resources that aren't associated with a catalog"
  []
  (time! (get-storage-metric :gc-params)
         (jdbc/delete!
          :resource_params_cache
          ["NOT EXISTS (SELECT * FROM catalog_resources cr
                          WHERE cr.resource=resource_params_cache.resource)"])))


(defn delete-unassociated-environments!
  "Remove any environments that aren't associated with a catalog, report or factset"
  []
  (time!
   (get-storage-metric :gc-environments)
   (jdbc/delete!
    :environments
    ["ID NOT IN
        (SELECT environment_id FROM catalogs WHERE environment_id IS NOT NULL
           UNION SELECT environment_id FROM reports
                   WHERE environment_id IS NOT NULL
           UNION SELECT environment_id FROM factsets
                   WHERE environment_id IS NOT NULL)"])))

(defn delete-unassociated-packages!
  []
  (time!
   (get-storage-metric :gc-packages)
   (jdbc/delete!
    :packages
    ["not exists (select * from certname_packages cp where cp.package_id = packages.id)"])))


;;; Packages

(defn find-certname-id-and-hash [certname]
  (first (jdbc/query-to-vec [(format "SELECT id, %s as package_hash FROM certnames WHERE certname=?"
                                     (sutils/sql-hash-as-str "package_hash"))
                             certname])))

(defn create-package-map [certname-id]
  (jdbc/call-with-query-rows ["SELECT id, name, version, provider FROM package_inventory WHERE certname_id=?"
                              certname-id]
                             (fn [rows]
                               (->> rows
                                    (map (fn [{id :id package_name :name version :version provider :provider}]
                                           [[package_name version provider] id]))
                                    (into {})))))

(defn find-package-hashes [package-hashes]
  (jdbc/call-with-query-rows [(format "SELECT id, %s as package_hash FROM packages WHERE hash = ANY(?)"
                                      (sutils/sql-hash-as-str "hash"))
                              (sutils/array-to-param "bytea" PGobject
                                                     (map sutils/munge-hash-for-storage package-hashes))]
                             (fn [rows]
                               (reduce (fn [acc {:keys [id package_hash]}]
                                         (assoc acc package_hash id))
                                       {} rows))))

(defn package-id-set-for-certname [certname-id]
  (jdbc/call-with-query-rows ["SELECT package_id FROM certname_packages WHERE certname_id=?"
                              certname-id]
                             (fn [rows]
                               (set (map :package_id rows)))))

(defn insert-missing-packages [existing-hashes-map new-hashed-package-tuples]
  (let [needed (remove (fn [hashed-package-tuple]
                         (get existing-hashes-map (pkg-util/package-tuple-hash hashed-package-tuple)))
                       new-hashed-package-tuples)
        insert (fn [rows]
                 (->> {:insert-into :packages
                       :values (map (fn [[package_name version provider package-hash]]
                                      {:name package_name
                                       :version version
                                       :provider provider
                                       :hash (sutils/munge-hash-for-storage package-hash)})
                                    rows)
                       :returning [:id]}
                      hsql/format
                      (nxt/plan (sql/get-connection jdbc/*db*))))
        ;; Insert all in batches while constructing one result vector ids
        new-ids (persistent! (reduce
                              (fn conj-batch [ids batch]
                                (reduce (fn conj-row [ids row] (conj! ids (:id row)))
                                        ids (insert batch)))
                              (transient [])
                              (partition-all 1000 needed)))]
    (merge existing-hashes-map (zipmap (map pkg-util/package-tuple-hash needed)
                                       new-ids))))

(s/defn update-packages
  "Compares `inventory` to the stored package inventory for
  `certname`. Differences will result in updates to the database"
  [certname_id :- s/Int
   package_hash :- (s/maybe s/Str)
   inventory :- [pkg-util/package-tuple]]
  (let [hashed-package-tuples (map shash/package-identity-hash inventory)
        new-package-hash (shash/package-similarity-hash hashed-package-tuples)]

    (when-not (= new-package-hash package_hash)
      (let [just-hashes (map pkg-util/package-tuple-hash hashed-package-tuples)
            existing-package-hashes (find-package-hashes just-hashes)
            full-hashes-map (insert-missing-packages existing-package-hashes hashed-package-tuples)
            new-package-id-set (set (vals full-hashes-map))
            [new-package-ids old-package-ids _] (clojure.data/diff new-package-id-set
                                                                   (package-id-set-for-certname certname_id))]
        (jdbc/update! :certnames
                      {:package_hash (when (seq inventory)
                                       (sutils/munge-hash-for-storage new-package-hash))}
                      ["id=?" certname_id])

        (doseq [id-batch (partition-all 1000 new-package-ids)]
          (->> {:insert-into :certname_packages
                :columns [:certname_id :package_id]
                :values (map #(vector certname_id %) id-batch)}
               hsql/format (nxt/execute! (jdbc/connection))))

        (when (seq old-package-ids)

          (let [old-package-id-sql-param (sutils/array-to-param "bigint" Long
                                                                (map long old-package-ids))]
            (jdbc/delete! :certname_packages
                          ["certname_id = ? and package_id = ANY(?)"
                           certname_id
                           old-package-id-sql-param])

            (jdbc/delete! :packages
                          [(str "id = any(?) "
                                "and not exists "
                                "(select 1 from certname_packages where package_id=id)")
                           old-package-id-sql-param])))))))

(defn insert-packages [certname inventory]
  (let [certname-id (:id (find-certname-id-and-hash certname))
        hashed-package-tuples (map shash/package-identity-hash inventory)
        new-packageset-hash (shash/package-similarity-hash hashed-package-tuples)
        just-hashes (map pkg-util/package-tuple-hash hashed-package-tuples)
        existing-package-hashes (find-package-hashes just-hashes)
        ;; a map of package hash to id in the packages table
        full-hashes-map (insert-missing-packages existing-package-hashes hashed-package-tuples)]

    (jdbc/update! :certnames
                  {:package_hash (sutils/munge-hash-for-storage new-packageset-hash)}
                  ["id=?" certname-id])

    (doseq [batch (partition-all 1000 (for [pkg-id (vals full-hashes-map)]
                                        [certname-id pkg-id]))]
      (->> {:insert-into :certname_packages
            :columns [:certname_id :package_id]
            :values batch}
           hsql/format (nxt/execute! (jdbc/connection))))))


;;; JSONB facts

(defn-validated certname-factset-metadata
  :- {:package_hash (s/maybe s/Str)
      :factset_id s/Int
      :certname_id s/Int
      :stable_hash (s/maybe (s/pred shash/sha1-bytes?))}
  "Given a certname, return the factset id, hash and certname id."
  [certname :- s/Str]
  (jdbc/query-with-resultset
   [(format "SELECT fs.id as factset_id, c.id as certname_id, %s as package_hash, fs.stable_hash as stable_hash
             FROM factsets fs, certnames c
             WHERE fs.certname = ? AND c.certname = ?"
            (sutils/sql-hash-as-str "c.package_hash"))
    certname certname]
   (comp first sql/result-set-seq)))


(defn volatile-fact-keys-for-factset [factset-id]
  (->> (jdbc/query-to-vec "select jsonb_object_keys(volatile) as fact from factsets where id=?"
                          factset-id)
       (map :fact)))

(defn load-stable-facts [factset-id]
  (-> (jdbc/query-to-vec "select stable from factsets where id=?"
                         factset-id)
      first
      :stable
      str
      json/parse-string))

;; Chunk size for path insertion to avoid blowing prepared statement size limit
(def path-insertion-chunk-size 6000)

(defn pathmap-digestor [^MessageDigest digest]
  (fn [{:keys [path value_type_id] :as pathmap}]
    (.update digest (-> (str path value_type_id)
                        (.getBytes "UTF-8")))
    pathmap))

(defn realize-paths
  "Ensures that every path in the pathmaps has a corresponding row in
  fact_paths."
  ([pathmaps] (realize-paths pathmaps nil))
  ([pathmaps notice-pathmap]
   (let [path->array #(sutils/array-to-param "text" String (map str %))
         pm->row (if notice-pathmap
                   #(update (notice-pathmap %) :path_array path->array)
                   #(update % :path_array path->array))]
     (doseq [batch (partition-all 1000 pathmaps)]
       (->> {:insert-into :fact_paths
             :values (map pm->row batch)
             :on-conflict [] :do-nothing true}
            hsql/format (nxt/execute! (jdbc/connection)))))))

(defn hash-pathmaps-paths [pathmaps]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (->> pathmaps
         (map (pathmap-digestor digest))
         dorun)
    (.digest digest)))

(defn factset-paths-hash [factset_id]
  (-> "select paths_hash from factsets where id = ?"
      (query-to-vec factset_id)
      first
      :paths_hash))

(pls/defn-validated add-facts!
  "Given a certname and a map of fact names to values, store records for those
  facts associated with the certname."
  ([{:keys [certname values environment timestamp producer_timestamp producer package_inventory]
     :as fact-data} :- facts-schema]
   (time!
    (get-storage-metric :add-new-fact)
    (jdbc/with-db-transaction []
      (let [paths-hash (let [digest (MessageDigest/getInstance "SHA-1")]
                         (realize-paths (facts/facts->pathmaps values)
                                        (pathmap-digestor digest))
                         (.digest digest))
            hash (shash/fact-identity-hash fact-data)]
        (->> {:insert-into :factsets
              :values [{:certname certname
                        :timestamp (to-timestamp timestamp)
                        :environment_id (ensure-environment environment)
                        :producer_timestamp (to-timestamp producer_timestamp)
                        :producer_id (ensure-producer producer)
                        :stable (sutils/munge-jsonb-for-storage values)
                        :stable_hash (shash/generic-identity-sha1-bytes values)
                        ;; need at least an empty map for the jsonb || operator
                        :volatile (sutils/munge-jsonb-for-storage {})
                        :paths_hash paths-hash
                        :hash (sutils/munge-hash-for-storage hash)}]}
             hsql/format (nxt/execute-one! (jdbc/connection)))
        (when (seq package_inventory)
          (insert-packages certname package_inventory))
        {:status :incorporated :hash hash})))))

(s/defn update-facts!
  "Given a certname, querys the DB for existing facts for that
   certname and will update, delete or insert the facts as necessary
   to match the facts argument. (cf. add-facts!)"
  [{:keys [certname values environment timestamp producer_timestamp producer package_inventory] :as fact-data}
   :- facts-schema]
  (jdbc/with-db-transaction []
    (let [{:keys [package_hash certname_id factset_id ^bytes stable_hash]}
          (certname-factset-metadata certname)

          ;; split facts into stable and volatile maps. Everything that was
          ;; previously volatile stays that way. Any stable value that changes
          ;; becomes volatile. Newly volatile facts are removed from the stable
          ;; map.
          current-volatile-fact-keys (volatile-fact-keys-for-factset factset_id)
          incoming-volatile-facts (select-keys values current-volatile-fact-keys)
          incoming-stable-facts (apply dissoc values current-volatile-fact-keys)
          ^bytes incoming-stable-fact-hash (shash/generic-identity-sha1-bytes incoming-stable-facts)
          fact-json-updates (if-not (Arrays/equals stable_hash incoming-stable-fact-hash)
                              ;; stable facts are different; load the json and move the ones that
                              ;; changed into volatile
                              (let [current-stable-facts (load-stable-facts factset_id)
                                    changed-facts (->> incoming-stable-facts
                                                       (filter (fn [[fact new-value]]
                                                                 (when-let [current (get current-stable-facts fact)]
                                                                   (not= new-value current))))
                                                       (into {}))
                                    new-stable-facts (->> incoming-stable-facts
                                                          (remove (fn [[fact _]] (get changed-facts fact)))
                                                          (into {}))
                                    new-volatile-facts (merge incoming-volatile-facts
                                                              changed-facts)]
                                {:stable (sutils/munge-jsonb-for-storage new-stable-facts)
                                 :stable_hash (shash/generic-identity-sha1-bytes new-stable-facts)
                                 :volatile (sutils/munge-jsonb-for-storage new-volatile-facts)})

                              ;; no change to stable facts, so just update volatile
                              {:volatile (sutils/munge-jsonb-for-storage incoming-volatile-facts)})]

      (when (or package_hash (seq package_inventory))
        (update-packages certname_id package_hash package_inventory))

      ;; Only update the paths if any existing paths_hash doesn't
      ;; match the incoming paths.
      (let [paths-hash (if-let [^bytes existing-hash (factset-paths-hash factset_id)]
                         (let [^bytes incoming-hash (-> (facts/facts->pathmaps values)
                                                 hash-pathmaps-paths)]
                           (if (Arrays/equals existing-hash incoming-hash)
                             existing-hash
                             (do
                               (realize-paths (facts/facts->pathmaps values))
                               incoming-hash)))
                         ;; No existing hash
                         (let [digest (MessageDigest/getInstance "SHA-1")]
                           (realize-paths (facts/facts->pathmaps values)
                                          (pathmap-digestor digest))
                           (.digest digest)))
            hash (shash/fact-identity-hash fact-data)]
        (jdbc/update! :factsets
                      (merge
                       {:timestamp (to-timestamp timestamp)
                        :environment_id (ensure-environment environment)
                        :producer_timestamp (to-timestamp producer_timestamp)
                        :hash (sutils/munge-hash-for-storage hash)
                        :producer_id (ensure-producer producer)
                        :paths_hash paths-hash}
                       fact-json-updates)
                      ["id=?" factset_id])
        {:status :incorporated :hash hash}))))

(defn delete-unused-fact-paths
  "Deletes paths from fact_paths that are no longer needed by any
  factset.  In the unusual case where a path changes type, the
  previous version will linger.  This requires a parent transaction
  with at least a repeatable-read isolation level (and may or may not
  need postgres' \"stronger than the standard\" repeatable-read
  behavior).  Otherwise paths could be added to fact_paths elsewhere
  during the gc, not be noticed, and then be deleted at the end."
  []
  ;; Use a temp table for now until we figure out why pg is creating a
  ;; vast number of temp files when this is all handled as a single
  ;; query.  (PDB-3924)
  (jdbc/do-commands
   ["with recursive live_paths(path, value) as"
    "   (select key as path, value"
    "      from (select (jsonb_each(stable||volatile)).* from factsets) as base_case"
    "      union"
    "        select path||'#~'||sub_level.key as path,"
    "               sub_level.value"
    "          from live_paths,"
    "          lateral (select *"
    "                     from (select (jsonb_each(value)).*"
    "                             where jsonb_typeof(value) = 'object') as sub_fields"
    "                     union (select generate_series(0, jsonb_array_length(value - 1))::text as key,"
    "                                   jsonb_array_elements(value) as value"
    "                              where jsonb_typeof(value) = 'array')) as sub_level)"
    "   select path into temp tmp_live_paths from live_paths"]

   "analyze tmp_live_paths"

   ["delete from fact_paths fp"
    "  where not exists (select 1 from tmp_live_paths"
    "                      where tmp_live_paths.path = fp.path)"]

   "drop table tmp_live_paths"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Reports

(defn find-containing-class
  "Given a containment path from Puppet, find the outermost 'class'."
  [containment-path]
  {:pre [(or
          (nil? containment-path)
          (and (coll? containment-path) (every? string? containment-path)))]
   :post [((some-fn nil? string?) %)]}
  (when-not ((some-fn nil? empty?) containment-path)
    ;; This is a little wonky.  Puppet only gives us an array of Strings
    ;; to represent the containment path.  Classes can be differentiated
    ;; from types because types have square brackets and a title; so, e.g.,
    ;; "Foo" is a class, but "Foo[Bar]" is a type with a title.
    (first
     (filter
      #(not (or (empty? %) (kitchensink/string-contains? "[" %)))
      (reverse containment-path)))))

(def store-resources-column? (atom false))
(def store-corrective-change? (atom false))

(defn replace-null-bytes [x]
  (if-not (string? x)
    x
    (let [^String s x]
      (if (= -1 (.indexOf s (int \u0000)))
        s
        (.replace s \u0000 \ufffd)))))

(defn normalize-resource-event
  "Prep `event` for comparison/computation of a hash"
  [event]
  (-> event
      (update :timestamp to-timestamp)
      (update :old_value (comp sutils/db-serialize replace-null-bytes))
      (update :new_value (comp sutils/db-serialize replace-null-bytes))
      (assoc :containing_class (find-containing-class (:containment_path event)))))

(defn normalize-report
  "Prep the report for comparison/computation of a hash"
  [{:keys [resources] :as report}]
  (-> report
      (update :start_time to-timestamp)
      (update :end_time to-timestamp)
      (update :producer_timestamp to-timestamp)
      (assoc :resource_events (->> resources
                                   reports/resources->resource-events
                                   (map normalize-resource-event)))))

(defn resource-event-expired?
  [timestamp resource-events-ttl]
  (time/after? (org.joda.time.DateTime. timestamp)
               (.minus (now) resource-events-ttl)))

(defn insert-resource-events [certname cert-id report-id resource-events ttl latest]
  (let [xf (comp (map #(assoc % :report_id report-id :certname_id cert-id :latest latest))
                 ;; resource-event-identity-pkey requires :report_id
                 (map #(assoc % :event_hash (-> (shash/resource-event-identity-pkey %)
                                                (sutils/munge-hash-for-storage))))
                 (map #(update % :containment_path (fn [x] (some-> x sutils/to-jdbc-varchar-array)))))
        xf (cond-> xf
             ttl (comp (filter #(resource-event-expired? (:timestamp %) ttl)))
             (not @store-corrective-change?) (comp (map #(dissoc % :corrective_change))))
        ;; group by the hash, and choose oldest (aka first) of any duplicates
        rows (->> (sequence xf resource-events) (group-by :event_hash)
                  vals (sort-by :timestamp) (map first))]
    (doseq [batch (partition-all 1000 rows)]
      (let [sql (-> {:insert-into :resource_events :values batch} hsql/format)]
        (try
          (nxt/execute! (jdbc/connection) sql)
          (catch SQLException ex
            (handle-resource-insert-sql-ex ex certname batch)))))))

(defn latest-report-metadata
 "Return the latest report timestamp for the given certname"
 [certname]
 (some-> ["select id as \"latest-report-id\", producer_timestamp as \"latest-ts\" from reports_latest where certname = ?"
          certname]
         query-to-vec
         first))

(defn latest-events-metadata
  [report-id]
  (some-> ["SELECT timestamp from resource_events_latest where report_id = ?"
           report-id]
          query-to-vec))

(s/defn add-report!* :- processing-status-schema
  "Helper function for adding a report.  Accepts an extra parameter, `update-latest-report?`, which
  is used to determine whether or not the `update-latest-report!` function will be called as part of
  the transaction.  This should always be set to `true`, except during some very specific testing
  scenarios."
  [orig-report :- reports/report-wireformat-schema
   received-timestamp :- pls/WireTimestamp
   save-event? :- s/Bool
   options-config]
  (time!
   (get-storage-metric :store-report)
   (let [{:keys [puppet_version certname report_format configuration_version producer
                 producer_timestamp start_time end_time transaction_uuid environment
                 status noop metrics logs resources resource_events catalog_uuid
                 code_id job_id cached_catalog_status noop_pending corrective_change
                 type]
          :as report} (normalize-report orig-report)
         report-hash (shash/report-identity-hash report)]
     (jdbc/with-db-transaction []
       (let [shash (sutils/munge-hash-for-storage report-hash)]
         (if (-> "select 1 from reports where encode(hash, 'hex'::text) = ? limit 1"
                 (query-to-vec report-hash)
                 seq)
           {:status :duplicate :hash report-hash}
           (let [certname-id (certname-id certname)
                 {:keys [latest-ts latest-report-id]} (latest-report-metadata certname)
                 latest (and (not= type "plan")
                             (or (nil? latest-ts)
                                 (.after producer_timestamp latest-ts)))
                 ttl (get options-config :resource-events-ttl)
                 row (cond-> {:hash shash
                              :transaction_uuid (sutils/munge-uuid-for-storage transaction_uuid)
                              :catalog_uuid (sutils/munge-uuid-for-storage catalog_uuid)
                              :code_id code_id
                              :job_id job_id
                              :cached_catalog_status cached_catalog_status
                              :metrics (sutils/munge-jsonb-for-storage metrics)
                              :logs (sutils/munge-jsonb-for-storage logs)
                              :noop noop
                              :noop_pending noop_pending
                              :puppet_version puppet_version
                              :certname certname
                              :report_format report_format
                              :configuration_version configuration_version
                              :producer_id (ensure-producer producer)
                              :producer_timestamp producer_timestamp
                              :start_time start_time
                              :end_time end_time
                              :receive_time (to-timestamp received-timestamp)
                              :status_id (ensure-status status)
                              :report_type (or type "agent")
                              :latest latest}
                       environment (assoc :environment_id (ensure-environment environment))
                       @store-resources-column? (assoc :resources (sutils/munge-jsonb-for-storage resources))
                       @store-corrective-change? (assoc :corrective_change corrective_change))
                 _ (when latest
                     ;; Mark existing latest report as historical
                     ;; If the partition does not exist throw ex-info to differentiate from a partition missing
                     ;; from the case where the incoming report needs partition(s) made for it.
                     (try
                       (jdbc/update! "resource_events" {:latest false} ["latest = true and report_id = ?" latest-report-id])
                       (jdbc/update! "reports" {:latest false} ["latest = true and certname = ?" certname])
                       (catch java.sql.BatchUpdateException e
                         ;; undefined table -- inheritance partitions, or declarative partitions w/ no children
                         ;; check constraint violation -- declarative partitions when row does not match child partitions
                         (if (#{:undefined-table :check-violation} (jdbc/sql-state-name (.getSQLState e)))
                           (throw (ex-info "No partition to move previous latest report into"
                                           {:kind ::missing-report-partition}
                                           e))
                           (throw e)))))

                 id (->> {:insert-into :reports :values [row] :returning [:id]}
                         hsql/format (select-one! (jdbc/connection) :id))]
             (when (and (seq resource_events) save-event?)
               (insert-resource-events certname certname-id id resource_events ttl latest))
             {:status :incorporated :hash report-hash})))))))

(defn maybe-log-query-termination
  "Takes a seq of maps containing information about PostgreSQL pids
   which the query-bulldozer attempted to call pg_terminate_backend(<pid>)
   on and logs information about the termination attempt.
   Example input: [{:pg_terminate_backend t/f :pid <pid>}]"
  [result]
  (let [{terminated true failed false} (group-by :pg_terminate_backend result)]
    (when (seq terminated)
      (log/info
       (trs "Partition GC terminated queries from the following PostgreSQL pids: {0}"
            (pr-str (mapv :pid terminated)))))
    (when (seq failed)
      (log/error
       (str
        (trs "Partition GC failed to terminate queries from the following PostgreSQL pids: {0}. "
             (pr-str (mapv :pid failed)))
        (trs "The queries related to these pids may be blocking other database operations."))))))

(defn query-bulldozer
  "Creates a thread which will loop and wait for the gc-pid to get blocked
   waiting on an AccessExclusiveLock. Once this thread detects that GC is
   blocked it will terminate all running queries from the pdb user against the
   pdb database which have been granted locks with the exception of queries
   from the gc-pid or the bulldozer's pid. This should clear the way for GC
   to grab the lock it's requesting in the main GC thread. This loop repeats
   until the bulldozer thread is interrupted or receives confirmation from the
   GC thread via the gc-finished? atom that the GC thread has dropped the
   partition."
  [db gc-pid connected gc-finished?]
  (let [bulldoze-blocking-qs
        (fn [gc-pid]
          (jdbc/query-to-vec
           (str "select pg_terminate_backend(pid), pid"
                " from pg_stat_activity"
                " where datname = (select current_database())"
                "   and pid in (select unnest(pg_blocking_pids(?)))")
           gc-pid))]
    (Thread.
     #(with-noisy-failure
        (try
          ;; The bulldozer must have its own connection so it can act
          ;; independenly of the (potentially blocked) gc connection,
          ;; i.e. (sql/db-find-connection db) should be false at this
          ;; point.
          (jdbc/with-db-connection db
            (deliver connected true)
            (while (not @gc-finished?)
              (maybe-log-query-termination (bulldoze-blocking-qs gc-pid))
              (Thread/sleep 200)))
          (catch InterruptedException _
            true)
          (finally
            (deliver connected false)))))))

(def gc-query-bulldozer-timeout-ms
  (env-config-for-db-ulong "PDB_GC_QUERY_BULLDOZER_TIMEOUT_MS"
                           (* 5 60 1000)))

(defn execute-with-bulldozer [db f]
  (if-not (pos? gc-query-bulldozer-timeout-ms)
    (f)
    (let [gc-pid (jdbc/current-pid)
          gc-finished? (atom false)
          bulldozer-connected (promise)
          gc-bulldozer (query-bulldozer db gc-pid bulldozer-connected gc-finished?)]
      (try
        (.start gc-bulldozer)
        (when @bulldozer-connected
          (f))
        (finally
          (when-not @bulldozer-connected
            (log/error (trs "Partition GC bulldozer could not connect to the database")))
          (reset! gc-finished? true)
          ;; make sure the gc-bulldozer thread has broken out of its loop
          (.interrupt gc-bulldozer)
          (.join gc-bulldozer gc-query-bulldozer-timeout-ms)
          (when (.isAlive gc-bulldozer)
            ;; if the join above timed out and the bulldozer thread is still alive
            ;; log an ERROR because it could indicate a memory leak
            (log/error "Unable to clean up the GC bulldozer thread.
                        Please file a bug with the stack trace below: \n"
                       (apply str (interpose "\n" (.getStackTrace gc-bulldozer))))))))))

(def daily-partition-drop-lock-timeout-ms
  (env-config-for-db-ulong "PDB_GC_DAILY_PARTITION_DROP_LOCK_TIMEOUT_MS"
                           (* 5 60 1000)))

(defn call-with-lock-timeout [f timeout-ms]
  (let [set-timeout #(->> (format "set local lock_timeout = %d" %)
                          (sql/execute! jdbc/*db*))
        ;; FIXME: possibly too crude...
        orig (-> "select setting from pg_settings where name = 'lock_timeout'"
                 query-to-vec first :setting Long/parseLong)]
    (set-timeout timeout-ms)
    (let [result (f)]
      ;; FIXME: For now we assume that when there's an exception,
      ;; the transaction's about to end, and that'll restore the
      ;; original value.  We don't use finally because we noticed
      ;; some trouble there, presumably during an exception that had
      ;; made restore operation invalid, and we didn't have time to
      ;; investigate.  Depending on what the issue was, we might be
      ;; able to put the set-timeout in a murphy finally block
      ;; (i.e. to chain the two exceptions).
      (set-timeout orig)
      result)))

;; Postgresql 14 allows DETACH PARTITION partition_name CONCURRENTLY with less locking.
(def pg14-db [14 0])

(defn detach-partitions-concurrently?
  "True if Postgresql server supports ALTER TABLE DETACH PARTITION CONCURRENTLY. (PG 14+)"
  []
  (let [{current-db-version :version} (sutils/db-metadata)]
    (not (neg? (compare current-db-version pg14-db)))))

(defn prune-daily-partitions
  "Either detaches or drops obsolete day-oriented partitions
  older than the date. Deletes or detaches only the oldest such candidate if
  incremental? is true. Will throw an SQLException termination if the operation
  takes much longer than PDB_GC_DAILY_PARTITION_DROP_LOCK_TIMEOUT_MS."
  ([table-prefix date incremental? update-lock-status status-key]
   (prune-daily-partitions table-prefix date incremental?
                           update-lock-status status-key false))
  ([table-prefix date incremental? update-lock-status status-key just-detach?]
   {:pre [(string? table-prefix)]}
   (let [utcz (ZoneId/of "UTC")
         expire-date (.withZoneSameInstant (time/joda-datetime->java-zoneddatetime date)
                                           utcz)
         expired? (fn [table]
                    (let [parts (str/split table #"_")
                          table-full-date (last parts)
                          table-year (Integer/parseInt (subs table-full-date 0 4))
                          table-month (Integer/parseInt (subs table-full-date 4 6))
                          table-day (Integer/parseInt (subs table-full-date 6 8))
                          table-date (ZonedDateTime/of table-year table-month table-day
                                                       0 0 0 0 utcz)]
                      (.isBefore table-date expire-date)))
         candidates (->> (partitioning/get-partition-names table-prefix)
                         (filter expired?)
                         sort)
         drop-one (fn [table]
                    (update-lock-status status-key inc)
                    (try!
                      (if just-detach?
                        (jdbc/do-commands-outside-txn
                          (format "alter table %s detach partition %s concurrently" table-prefix table))
                        (jdbc/do-commands
                          (format "drop table if exists %s cascade" table)))
                      (finally
                        (update-lock-status status-key dec))))
         drop #(if incremental?
                 (when-let [[candidate & _] (seq candidates)]
                   (log/info (trs "Partition GC {0} partition {1}"
                                  (if just-detach? "detaching" "dropping")
                                  candidate))
                   (drop-one candidate)
                   (log/info (trs "Partition GC {0} partition {1}"
                                  (if just-detach? "detached" "dropped")
                                  candidate))
                   (when (> (bounded-count 3 candidates) 2)
                     (log/warn (trs "More than 2 partitions remaining to prune: {0}"
                                    (pr-str (butlast candidates)))))
                   [candidate])
                 (do
                   (doseq [candidate candidates]
                     (drop-one candidate))
                   candidates))]
     (if-not daily-partition-drop-lock-timeout-ms
       (drop)
       (call-with-lock-timeout drop daily-partition-drop-lock-timeout-ms)))))

(defn detach-daily-partitions
  "Detaches declarative partitions concurrently in PG14+, which runs with a
  less restrictive SHARE UPDATE EXCLUSIVE lock instead of ACCESS EXCLUSIVE.
  Once it's detached, it should no longer participate in queries to the parent
  table and the subsequent drop should be able to acquire an ACCESS EXCLUSIVE
  lock more quickly.

  NOTE: This must be run outside of a transaction."
  [table-prefix date incremental? update-lock-status status-key]
  (prune-daily-partitions table-prefix date incremental?
                          update-lock-status status-key true))

(defn drop-partition-tables!
  "Drops the given set of tables. Will throw an SQLException termination if the
  operation takes much longer than PDB_GC_DAILY_PARTITION_DROP_LOCK_TIMEOUT_MS."
  [old-partition-tables update-lock-status status-key]
  (let [drop #(doseq [table old-partition-tables]
                 (try
                   (update-lock-status status-key inc)
                   (jdbc/do-commands
                     (format "drop table if exists %s cascade" table))
                   (finally
                     (update-lock-status status-key dec))))]
    (if-not daily-partition-drop-lock-timeout-ms
      (drop)
      (call-with-lock-timeout drop daily-partition-drop-lock-timeout-ms))))

(defn delete-resource-events-older-than!
  "Delete all resource events in the database by dropping any partition older than the day of the year of the given
  date.
  Note: this ignores the time in the given timestamp, rounding to the day."
  [date incremental? update-lock-status]
  (if-not (detach-partitions-concurrently?)
    ;; PG11
    (jdbc/with-db-transaction []
      (prune-daily-partitions "resource_events" date incremental?
                              update-lock-status :write-locking-resource-events))
    ;; PG14+
    (let [detached-tables
           (detach-daily-partitions "resource_events" date incremental?
                                    update-lock-status :write-locking-resource-events)]
      (jdbc/with-db-transaction []
        (drop-partition-tables! detached-tables
                                update-lock-status :write-locking-resource-events)))))

(defn cleanup-dropped-report-certnames
  "Since we cannot cascade back to the certnames table anymore, go clean up
  the latest_report_id column after a GC."
  []
  (jdbc/do-commands
   ["UPDATE certnames SET latest_report_id = NULL"
    "  WHERE certname IN"
    "    (SELECT DISTINCT certnames.certname FROM certnames"
    "       LEFT OUTER JOIN reports ON (reports.certname = certnames.certname)"
    "       WHERE reports.id IS NULL)"]))

(defn delete-reports-older-than-in-pg-11!
  "PostgreSQL 11 workflow for deleting all reports in the database which have
  a producer-timestamp that is prior to the specified report-time.  When
  event-time is specified, delete all the events that are older than whichever
  time is more recent."
  [report-ttl resource-events-ttl incremental? update-lock-status]
  (jdbc/with-db-transaction []
    (acquire-locks! {"certnames" "ROW EXCLUSIVE"
                     "reports" "ACCESS EXCLUSIVE"
                     "resource_events" "ACCESS EXCLUSIVE"})
    (prune-daily-partitions "resource_events" resource-events-ttl
                            incremental? update-lock-status
                            :write-locking-resource-events)
    (prune-daily-partitions "reports" report-ttl
                            incremental? update-lock-status
                            :write-locking-reports)
    (cleanup-dropped-report-certnames)))

(defn delete-reports-older-than!
  "Delete all reports in the database which have a producer-timestamp
  that is prior to the specified report-time. When event-time is
  specified, delete all the events that are older than whichever time
  is more recent."
  [{:keys [report-ttl resource-events-ttl incremental? update-lock-status]
    :or {resource-events-ttl report-ttl
         incremental? false
         update-lock-status (constantly true)}}]
  {:pre [(instance? org.joda.time.DateTime report-ttl)
         (instance? org.joda.time.DateTime resource-events-ttl)
         (ifn? update-lock-status)]}
  (let [effective-resource-events-ttl
         (if (before? report-ttl resource-events-ttl) resource-events-ttl report-ttl)]
    (if-not (detach-partitions-concurrently?)
      ;; PG11
      (delete-reports-older-than-in-pg-11! report-ttl effective-resource-events-ttl
                                           incremental? update-lock-status)
      ;; PG14+
      ;; Detach partition concurrently must take place outside of a transaction.
      (let [detached-resource-event-tables
             (detach-daily-partitions "resource_events" effective-resource-events-ttl
                                      incremental? update-lock-status
                                      :write-locking-resource-events)
            detached-report-tables
             (detach-daily-partitions "reports" report-ttl
                                    incremental? update-lock-status
                                    :write-locking-reports)]
        ;; Now we can delete the partitions with less intrusive locking.
        (jdbc/with-db-transaction []
          ;; Nothing should acquire locks on the detached tables, but to be safe, acquire
          ;; lock on certnames first since that is generally the order we acquire locks,
          ;; and maintaining that order is a good practice for avoiding deadlocks.
          (acquire-locks! {"certnames" "ROW EXCLUSIVE"})
          ;; force a resource-events GC. prior to partitioning, this would have happened
          ;; via a cascade when the report was deleted, but now we just drop whole tables
          ;; of resource events.
          (drop-partition-tables! detached-resource-event-tables
                                  update-lock-status :write-locking-resource-events)
          (drop-partition-tables! detached-report-tables
                                  update-lock-status :write-locking-reports)
          ;; since we cannot cascade back to the certnames table anymore, go clean up
          ;; the latest_report_id column after a GC.
          (cleanup-dropped-report-certnames))))))

;; A db version that is "allowed" but not supported is deprecated
(def oldest-allowed-db [11 0])

(def oldest-supported-db [14 0])

(defn timestamp-of-newest-record [entity certname]
  (let [query {:select [:producer_timestamp]
               :from [entity]
               :where [:= :certname certname]
               :order-by [[:producer_timestamp :desc]]
               :limit 1}]
    (:producer_timestamp (first (jdbc/query (hsql/format query))))))

(pls/defn-validated have-newer-record-for-certname?
  "Returns a truthy value indicating whether a record exists that has
  a producer_timestamp newer than the given timestamp."
  [certname :- String
   timestamp :- Timestamp]
  ;; In order for this query to get the right answer using "order by producer_timestamp desc"
  ;; The subqueries must not return any null producer_timestamps because
  ;; null sorts as "higher" than real values.
  (when-let [newest (-> (str "select producer_timestamp"
                             "  from (select producer_timestamp from catalogs"
                             "          where certname = ?"
                             "          order by producer_timestamp desc limit 1) cats"
                             "  union all (select producer_timestamp from factsets"
                             "               where certname = ?"
                             "               order by producer_timestamp desc limit 1)"
                             "  union all (select producer_timestamp from reports_latest"
                             "               where certname = ?)"
                             "  order by producer_timestamp desc"
                             "  limit 1")
                        (cons (repeat 3 certname))
                        vec
                        (jdbc/query-to-vec)
                        first
                        :producer_timestamp)]
    (.after newest timestamp)))

(pls/defn-validated maybe-activate-node!
  "Reactivate the given host, only if it was deactivated or expired before
  `time`.  Returns true if the node is activated, or if it was already active.

  Adds the host to the database if it was not already present."
  [certname :- String
   time :- pls/Timestamp]
  (let [timestamp (to-timestamp time)

        {:keys [deactivated expired] :as node}
        (jdbc/query-with-resultset
         ["SELECT deactivated,expired FROM certnames_status WHERE certname=?" certname]
         (comp first sql/result-set-seq))]

    ;; add node if missing, or update deactivated/expired status if necessary
    ;; this introduces a small read-then-update race condition where a node could
    ;; be deactivated in between the previous read and this check and we wouldn't
    ;; reset the node to active. But if the node is still submitting commands it
    ;; will be reactivated the following time, and if it isn't submitting commands
    ;; then a deactivated/expired status is likely correct.
    (cond
     ;; node is active
     (and node (nil? deactivated) (nil? expired)) true

     ;; node is deactived/expired with newer timestamp
     (and node
          (or (and deactivated (.after deactivated timestamp))
              (and expired (.after expired timestamp))))
     false

     :else (do
             (jdbc/do-prepared "insert into certnames (certname) values (?) on conflict do nothing"
               [certname])
             (-> (jdbc/do-prepared
                  (str "insert into certnames_status (certname) values (?)"
                       "  on conflict (certname) do update set deactivated=null, expired=null"
                       "  where (certnames_status.deactivated < ? or certnames_status.expired < ?)")
                  [certname timestamp timestamp])
               first
               pos?)))))

(pls/defn-validated deactivate-node!
  "Deactivate the given host, recording the current time. If the node is
  currently inactive, no change is made."
  ([certname :- String]
   (deactivate-node! certname (now)))
  ([certname :- String timestamp :- pls/Timestamp]
   (let [sql-timestamp (to-timestamp timestamp)]
     (if (have-newer-record-for-certname? certname sql-timestamp)
       {:status :stale}
       (do
         (jdbc/do-prepared "UPDATE certnames_status SET deactivated = ?
                         WHERE certname=?
                         AND (deactivated IS NULL OR deactivated < ?)"
                           [sql-timestamp certname sql-timestamp])
         {:status :incorporated})))))

(pls/defn-validated expire-stale-nodes
  "Expires nodes with no activity within the provided horizon (prior
  to now) and returns a collection of the affected certnames."
  [horizon :- Period]
  (let [stale-start-ts (to-timestamp (ago horizon))
        expired-ts (to-timestamp (now))]
    (map :certname
         (jdbc/query-to-vec
          (str
           "update certnames_status set expired = ?"
           "  where certname in"
           "    (select c.certname from certnames c
                     left outer join certnames_status cs on cs.certname = c.certname
                     left outer join catalogs cats on cats.certname = c.certname
                     left outer join factsets fs on c.certname = fs.certname
                     left outer join reports r on c.latest_report_id = r.id
                     left outer join certname_fact_expiration cfe on c.id = cfe.certid
                     left outer join catalog_inputs ci on c.id = ci.certname_id
                   where cs.deactivated is null
                     and cs.expired is null
                     and (cats.producer_timestamp is null
                          or cats.producer_timestamp < ?)
                     and (fs.producer_timestamp is null
                          or fs.producer_timestamp < ?)
                     and (r.producer_timestamp is null
                          or r.producer_timestamp < ?)"
           "         and (cfe.updated is null"
           "              or (cfe.expire and cfe.updated < ?))"
           "         and (c.catalog_inputs_timestamp is null"
           "              or c.catalog_inputs_timestamp < ?))"
           "  returning certname")
          expired-ts
          stale-start-ts stale-start-ts stale-start-ts stale-start-ts stale-start-ts))))

(pls/defn-validated replace-facts! :- processing-status-schema
  "Updates the facts of an existing node, if the facts are newer than the current set of facts.
   Adds all new facts if no existing facts are found. Invoking this function under the umbrella of
   a repeatable read or serializable transaction enforces only one update to the facts of a certname
   can happen at a time.  The first to start the transaction wins.  Subsequent transactions will fail
   as the factsets will have changed while the transaction was in-flight."
  [{:keys [certname producer_timestamp] :as fact-data} :- facts-schema]
  (time! (get-storage-metric :replace-facts)
         (if-let [local-factset-producer-ts (timestamp-of-newest-record :factsets certname)]
           (if-not (.after local-factset-producer-ts (to-timestamp producer_timestamp))
             (update-facts! fact-data)
             {:status :stale})
           (add-facts! fact-data))))

(s/defn add-report! :- processing-status-schema
  "Add a report and all of the associated events to the database."
  ([report received-timestamp db conn-status]
   (add-report! report received-timestamp db conn-status true))
  ([{:keys [certname producer_timestamp] :as report} :- reports/report-wireformat-schema
   received-timestamp :- pls/Timestamp
   db conn-status
   options-config]
  (let [producer-timestamp (to-timestamp producer_timestamp)
        resource-events-ttl (get options-config :resource-events-ttl)
        save-event (if (nil? resource-events-ttl)
                       true
                       (not (== 0 (.getSeconds (.toStandardSeconds resource-events-ttl)))))
        store! (fn []
                 (jdbc/retry-with-monitored-connection
                  db conn-status {:isolation :read-committed
                                  :statement-timeout command-sql-statement-timeout-ms}
                  (fn []
                    (maybe-activate-node! certname producer-timestamp)
                    (add-report!* report received-timestamp save-event options-config))))]
    (try
      (store!)
      (catch ExceptionInfo e
        (if (not= ::missing-report-partition (:kind (ex-data e)))
          (throw e)
          (do
            (jdbc/retry-with-monitored-connection
              db conn-status {:isolation :read-committed
                              :statement-timeout command-sql-statement-timeout-ms}
              (fn []
                (let [{:keys [latest-ts latest-report-id]} (latest-report-metadata certname)
                      event-timestamps (map :timestamp (latest-events-metadata latest-report-id))]
                  (partitioning/create-reports-partition latest-ts)
                  (doseq [date event-timestamps]
                    (partitioning/create-resource-events-partition date)))))
            ;; Now that the partitions exist, attempt store the report again
            (store!))))
      (catch org.postgresql.util.PSQLException e
        ;; undefined table -- inheritance partitions, or declarative partitions w/ no children
        ;; check constraint violation -- declarative partitions when row does not match child partitions
        (if (#{:undefined-table :check-violation} (jdbc/sql-state-name (.getSQLState e)))
          (do
            ;; One or more partitions didn't exist, so attempt to create all
            ;; the partitions this report and its resource_events need
            (jdbc/retry-with-monitored-connection
             db conn-status {:isolation :read-committed
                             :statement-timeout command-sql-statement-timeout-ms}
             (fn []
               (partitioning/create-reports-partition producer-timestamp)
               (doseq [date (set (map :timestamp
                                      (:resource_events (normalize-report report))))]
                 (partitioning/create-resource-events-partition date))))
            ;; Now that the partitions exist, attempt store the report again
            (store!))
          ;; otherwise throw the error so the command ends up
          ;; in the DLO
          (throw e)))))))

(def fact-path-gc-lock-timeout-ms
  (env-config-for-db-ulong "PDB_FACT_PATH_GC_SQL_LOCK_TIMEOUT_MS" nil))

(defn clean-catalog-data
  "Delete unassociated resource parameters and environments."
  [db]
  (jdbc/with-transacted-connection db
    (delete-unassociated-params!)
    (delete-unassociated-environments!)))

(defn fact-path-gc [db]
  (jdbc/with-transacted-connection' db :repeatable-read
    ;; May or may not require postgresql's "stronger than the
    ;; standard" behavior for repeatable read.
    (try
      (some->> fact-path-gc-lock-timeout-ms
               (format "set local lock_timeout = %d")
               (sql/execute! jdbc/*db*))
      (delete-unused-fact-paths)
      (catch SQLException ex
        (when-not (= (jdbc/sql-state :admin-shutdown) (.getSQLState ex))
          (throw ex))
        (log/warn (trs "sweep of unused fact paths timed out"))))))

(defn garbage-collect!
  "Delete any lingering, unassociated data in the database"
  [db]
  (time! (get-storage-metric :gc db)
    (clean-catalog-data db)
    (fact-path-gc db)))
