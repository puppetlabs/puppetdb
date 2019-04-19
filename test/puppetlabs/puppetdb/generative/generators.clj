(ns puppetlabs.puppetdb.generative.generators
  (:require
   [puppetlabs.puppetdb.generative.overridable-generators :as gen :refer [defgen]]
   [puppetlabs.puppetdb.time :as time]))

;;; common data types and combinators

(defn string+
  ([] (string+ 255))
  ([max-len] (gen/fmap clojure.string/join (gen/vector gen/char-alphanumeric 1 max-len))))

(def pg-smallint (gen/choose -32768 32767))
(def pg-integer (gen/choose -2147483648 2147483647))
(def pg-pos-integer (gen/choose 1 2147483647))
(def pg-bigint (gen/choose -9223372036854775808 9223372036854775807))

(def uuid-string (gen/fmap str gen/uuid))

(def datetime
  (gen/fmap time/from-long
        (gen/choose 50000000000 2000000000000)))

(def json-value
  (let [leaf-gen (gen/one-of [(string+)
                              pg-integer
                              gen/boolean])]
    (gen/one-of [leaf-gen
                 (gen/recursive-gen
                  (fn [inner-gen] (gen/one-of [(gen/vector inner-gen)
                                               (gen/map (string+) inner-gen)]))
                  leaf-gen)])))

;;; catalog generator

(defgen :puppet/certname (string+))
(defgen :puppet/environment (string+))
(defgen :puppet/transaction_uuid uuid-string)
(defgen :puppet/catalog_uuid uuid-string)
(defgen :puppet/producer_timestamp (string+))
(defgen :puppet/producer (string+))

(defgen :catalog/code_id (gen/nilable (string+)))
(defgen :catalog/job_id (gen/nilable (string+)))
(defgen :catalog/version (gen/fmap str gen/nat))

(defgen :edge/resource-spec (gen/keys :resource/type :resource/title))
(defgen :edge/source :edge/resource-spec)
(defgen :edge/target :edge/resource-spec)
(defgen :edge/relationship
  (gen/elements ["contains"
                 "required-by"
                 "notifies"
                 "before"
                 "subscription-of"]))

(defgen :catalog/edge (gen/keys :edge/source :edge/target :edge/relationship))
(defgen :catalog/edges (gen/vector :catalog/edge))

(defgen :resource/type (string+))
(defgen :resource/title (string+))
(defgen :resource/exported gen/boolean)
(defgen :resource/file (string+ 1024))
(defgen :resource/line pg-pos-integer)

(defgen :resource/tag  (gen/string-from-regex #"[a-z0-9_][a-z0-9_:\-.]*"))
(defgen :resource/tags (gen/vector :resource/tag 0 3))

(defgen :resource.param/name (string+ 40))
(defgen :resource.param/value
  (gen/recursive-gen
   (fn [inner-gen]
     (gen/one-of [(gen/map (string+) inner-gen)
                  (gen/vector inner-gen)]))
   (gen/one-of [(string+)
                pg-smallint
                gen/boolean])))

(defgen :resource/parameters (gen/map :resource.param/name :resource.param/value))
(defgen :catalog/resource (gen/keys :resource/type
                                    :resource/title
                                    :resource/exported
                                    :resource/file
                                    :resource/line
                                    :resource/parameters
                                    :resource/tags))

(defgen :catalog/resources
  (gen/vector-distinct :catalog/resource))

(defgen :catalog/resource-tree
  (gen/recursive-gen
   (fn [inner-gen]
     (gen/hash-map :children (gen/vector inner-gen)
                   :resource :catalog/resource
                   :relationship :edge/relationship))
   (gen/keys :catalog/resource)))

(defn resource-tree-to-edge-list [rt]
  (let [nodes (tree-seq :children :children rt)
        resources (map :resource nodes)
        edges (->> nodes
                   (filter :children)
                   (mapcat (fn [node]
                             (let [source (:resource node)
                                   target-nodes (:children node)
                                   relationship (:relationship node)]
                               (map (fn [target-node]
                                      {:source (select-keys source [:type :title])
                                       :target (select-keys (:resource target-node) [:type :title])
                                       :relationship relationship})
                                    target-nodes)))))]
    {:resources resources
     :edges edges}))

(defgen :puppet/catalog
  (gen/fmap (fn [cat]
              (-> cat
                  (dissoc :resource-tree)
                  (merge (resource-tree-to-edge-list (:resource-tree cat)))))
            (gen/keys :puppet/certname
                      :puppet/environment
                      :puppet/transaction_uuid
                      :puppet/catalog_uuid
                      :puppet/producer_timestamp
                      :puppet/producer
                      :catalog/version
                      :catalog/code_id
                      :catalog/job_id
                      :catalog/resource-tree)))

;;; factset generator

(defgen :fact/name (string+))
(defgen :fact/value json-value)
(defgen :fact/values (gen/map :fact/name :fact/value))

(defgen :puppet/factset
  (gen/keys :puppet/certname
            :puppet/environment
            :puppet/producer_timestamp
            :puppet/producer
            :fact/values))

;;; report generator

(defgen :report/puppet_version (string+))
(defgen :report/configuration_version (string+))
(defgen :report/start_time datetime)
(defgen :report/end_time datetime)
(defgen :report/status (string+ 40))
(defgen :report/noop gen/boolean)
(defgen :report/corrective_change (gen/nilable gen/boolean))

(defgen :resource-event/status (gen/elements ["success" "failure" "noop" "skipped"]))
(defgen :resource-event/timestamp datetime)
(defgen :resource-event/property :resource.param/name)
(defgen :resource-event/new_value :resource.param/value)
(defgen :resource-event/old_value :resource.param/value)
(defgen :resource-event/message (string+))
(defgen :resource-event/containment_path (gen/vector (string+)))
(defgen :resource-event/corrective_change (gen/nilable gen/boolean))

(defgen :report-resource/event
  (gen/keys :resource-event/status
            :resource-event/timestamp
            :resource-event/property
            :resource-event/new_value
            :resource-event/old_value
            :resource-event/message
            :resource-event/corrective_change))

(defgen :report-resource/events
  (gen/vector-distinct-by :property
                          :report-resource/event
                          {:min-elements 1 :max-elements 10}))

(defgen :report-resource/timestamp datetime)
(defgen :report-resource/resource_type :resource/type)
(defgen :report-resource/resource_title :resource/title)
(defgen :report-resource/skipped gen/boolean)
(defgen :report-resource/file :resource/file)
(defgen :report-resource/line :resource/line)
(defgen :report-resource/containment_path :resource-event/containment_path)

(defgen :report/resource
  (gen/keys :report-resource/timestamp
            :report-resource/resource_type
            :report-resource/resource_title
            :report-resource/skipped
            :report-resource/events
            :report-resource/file
            :report-resource/line
            :report-resource/containment_path))

(defgen :report/resources
  (gen/vector-distinct-by (juxt :resource_type :resource_title)
                          :report/resource
                          {:min-elements 0
                           :max-elements 10}))

(defgen :metric/category (gen/elements ["resources" "time" "changes" "events"]))
(defgen :metric/name (string+))
(defgen :metric/value gen/double)

(defgen :report/metric
  (gen/keys :metric/category :metric/name :metric/value))

(defgen :report/metrics
  (gen/vector :report/metric 1 10))

(defgen :log/file :resource/file)
(defgen :log/line :resource/line)
(defgen :log/level (gen/elements ["info" "warn" "error" "debug"]))
(defgen :log/message (string+))
(defgen :log/source (string+))
(defgen :log/tags :resource/tags)
(defgen :log/time datetime)

(defgen :report/log
  (gen/keys :log/file
            :log/line
            :log/level
            :log/message
            :log/source
            :log/tags
            :log/time))

(defgen :report/logs
  (gen/vector :report/log 1 10))

(defgen :report/cached_catalog_status
  (gen/return nil))

(defgen :report/report_format
  (gen/return 4))

(defgen :puppet/report
  (gen/keys :puppet/certname
            :puppet/environment
            :puppet/transaction_uuid
            :puppet/catalog_uuid
            :report/puppet_version
            :report/configuration_version
            :report/start_time
            :puppet/producer_timestamp
            :report/end_time
            :report/status
            :report/noop
            :report/resources
            :report/metrics
            :report/logs
            :catalog/code_id
            :catalog/job_id
            :report/cached_catalog_status
            :report/corrective_change
            :report/report_format))

;;; command generator

(defgen :command/received (string+))

(defgen :puppetdb.commands/replace-catalog
  (gen/fmap #(assoc %
                    :version (Integer/parseInt (-> % :payload :version))
                    :certname (-> % :payload :certname))
            (gen/hash-map :command (gen/return "replace catalog")
                          :payload :puppet/catalog
                          :received :command/received)))

(defgen :puppetdb.commands/replace-facts
  (gen/fmap #(assoc %
                    :certname (-> % :payload :certname))
            (gen/hash-map :command (gen/return "replace facts")
                          :version (gen/return 5)
                          :payload :puppet/factset
                          :received :command/received)))

(defgen :puppetdb.commands/store-report
  (gen/fmap #(assoc %
                    :certname (-> % :payload :certname))
            (gen/hash-map :command (gen/return "store report")
                          :version (gen/return 7)
                          :payload :puppet/report
                          :received :command/received)))

(defgen :puppetdb/command
  (gen/one-of [:puppetdb.commands/replace-catalog
               :puppetdb.commands/replace-facts
               :puppetdb.commands/store-report]))
