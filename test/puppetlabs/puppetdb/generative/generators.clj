(ns puppetlabs.puppetdb.generative.generators
  (:require [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck.generators :as chuck-gen]
            [clj-time.core :as time :refer [now]]))


;;; common data types and combinators
(defn nilable [g]
  (gen/one-of [(gen/return nil) g]))

(defn string+
  ([]
   (gen/fmap clojure.string/join (gen/vector gen/char-alphanumeric 1 255)))
  ([max-len]
   (gen/fmap clojure.string/join (gen/vector gen/char-alphanumeric 1 max-len))))

(defn pg-smallint [] (gen/choose -32768 32767))
(defn pg-integer [] (gen/choose -2147483648 2147483647))
(defn pg-pos-integer [] (gen/choose 1 2147483647))
(defn pg-bigint [] (gen/choose -9223372036854775808 9223372036854775807))

(defn uuid-string [] (gen/fmap str gen/uuid))

(defn datetime []
  (gen/fmap clj-time.coerce/from-long
            (gen/choose 50000000000 2000000000000)))



;;; catalog generator

(defn certname [] (string+))
(defn environment [] (string+))
(defn transaction_uuid [] (uuid-string))
(defn catalog_uuid [] (uuid-string))
(defn producer_timestamp [] (string+))

(defn producer [] (string+))
(defn code_id [] (nilable (string+)))

(defn version [] (string+))

(defn resource-type [] (string+))
(defn resource-title [] (string+))
(defn resource_spec []
  (gen/hash-map :type (resource-type)
                :title (resource-title)))

(defn source [] (resource_spec))
(defn target [] (resource_spec))
(defn relationship []
  (gen/one-of (map gen/return
                   ["contains"
                    "required-by"
                    "notifies"
                    "before"
                    "subscription-of"])))

(defn edge []
  (gen/hash-map :source (source)
                :target (target)
                :relationship (relationship)))

(defn edges [] (gen/vector (edge)))

(defn exported [] gen/boolean)
(defn file [] (string+ 1024))
(defn line [] (pg-pos-integer))

(defn tag [] (chuck-gen/string-from-regex #"[a-z0-9_][a-z0-9_:\-.]*"))
(defn tags [] (gen/vector (tag) 0 3))

(defn resource-param-name [] (string+ 40))

(defn resource-param-value []
  (gen/recursive-gen
   (fn [inner-gen]
     (gen/one-of [(gen/map (string+) inner-gen)
                  (gen/vector inner-gen)]))
   (gen/one-of [(string+)
                (pg-smallint)
                gen/boolean])))

(defn resource-parameters []
  (gen/map (resource-param-name) (resource-param-value)))

(defn resource []
  (gen/hash-map :type (resource-type)
                :title (resource-title)
                :exported (exported)
                :file (file)
                :line (line)
                :parameters (resource-parameters)
                :tags (tags)))

(defn resources []
  (gen/vector-distinct (resource)))

(defn resource-tree []
  (gen/recursive-gen
   (fn [inner-gen]
     (gen/hash-map :children (gen/vector inner-gen)
                   :resource (resource)
                   :relationship (relationship)))
   (gen/fmap (fn [r] {:resource r})
             (resource))))

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

(defn catalog []
  (gen/fmap (fn [cat]
              (-> cat
                  (dissoc :resource-tree)
                  (merge (resource-tree-to-edge-list (:resource-tree cat)))))
            (gen/hash-map :certname (certname)
                          :version (version)
                          :environment (environment)
                          :transaction_uuid (transaction_uuid)
                          :catalog_uuid (catalog_uuid)
                          :producer_timestamp (producer_timestamp)
                          :producer (producer)
                          :code_id (code_id)
                          ;; :edges (edges)
                          ;; :resources (resources)
                          :resource-tree (resource-tree))))

;;; factset generator

(defn json-value []
  (gen/recursive-gen
   (fn [inner-gen]
     (gen/one-of [(gen/vector inner-gen)
                  (gen/map (string+) inner-gen)]))
   (gen/one-of [(string+)
                (pg-integer)
                gen/boolean])))

(defn fact-name [] (string+))
(defn fact-value [] (json-value))

(defn factset []
  (gen/hash-map :certname (certname)
                :environment (environment)
                :producer_timestamp (producer_timestamp)
                :producer (producer)
                :values (gen/map (fact-name)
                                 (fact-value))))

;;; report generator

(defn puppet_version [] (string+))
(defn configuration_version [] (string+))
(defn start_time [] (datetime))
(defn end_time [] (datetime))
(defn status [] (string+ 40))
(defn noop [] gen/boolean)
(defn corrective_change [] (nilable gen/boolean))

(defn resource-event-status [] (gen/elements ["success" "failure" "noop" "skipped"]))
(defn resource-event-timestamp [] (datetime))
(defn resource-event-property [] (resource-param-name))
(defn resource-event-new-value [] (resource-param-value))
(defn resource-event-old-value [] (resource-param-value))
(defn resource-event-message [] (string+))
(defn resource-event-containment-path [] (gen/vector (string+)))

(defn resource-event []
  (gen/hash-map :status (resource-event-status)
                :timestamp (resource-event-timestamp)
                :property (resource-event-property)
                :new_value (resource-event-new-value)
                :old_value (resource-event-old-value)
                :message (resource-event-message)
                :corrective_change (corrective_change)))

(defn resource-events []
  (gen/vector-distinct-by :property (resource-event)
                          {:min-elements 1
                           :max-elements 10}))

(defn resource-timestamp [] (datetime))
(defn resource-skipped [] gen/boolean)

(defn report-resource []
  (gen/hash-map :timestamp (resource-timestamp)
                :resource_type (resource-type)
                :resource_title (resource-title)
                :skipped (resource-skipped)
                :events (resource-events)
                :file (file)
                :line (line)
                :containment_path (resource-event-containment-path)))

(defn report-resources  []
  (gen/vector-distinct-by (juxt :resource_type :resource_title)
                          (report-resource)
                          {:min-elements 0
                           :max-elements 10}))

(defn metric-category [] (gen/elements ["resources" "time" "changes" "events"]))
(defn metric-name [] (string+))
(defn metric-value [] gen/double)

(defn metric []
  (gen/hash-map :category (metric-category)
                :name (metric-name)
                :value (metric-value)))

(defn metrics []
  (gen/vector (metric) 1 10))

(defn log-level [] (gen/elements ["info" "warn" "error" "debug"]))
(defn log-source [] (string+))
(defn log-timestamp [] (datetime))
(defn log-message [] (string+))

(defn log []
  (gen/hash-map :file (file)
                :line (line)
                :level (log-level)
                :message (log-message)
                :source (log-source)
                :tags (tags)
                :time (log-timestamp)))

(defn logs []
  (gen/vector (log) 1 10))

(defn report []
  (gen/hash-map :certname (certname)
                :environment (environment)
                :puppet_version (puppet_version)
                :configuration_version (configuration_version)
                :start_time (start_time)
                :end_time (end_time)
                :producer_timestamp (producer_timestamp)
                :transaction_uuid (transaction_uuid)
                :status (status)
                :noop (noop)
                :resources (report-resources)
                :metrics (metrics)
                :logs (logs)
                :transaction_uuid (transaction_uuid)
                :catalog_uuid (catalog_uuid)
                :code_id (code_id)
                :cached_catalog_status (gen/return nil)
                :corrective_change (corrective_change)
                :report_format (gen/return 4)))

;;; command generator

(defn command-received []
  (string+))

(defn replace-catalog-command []
  (gen/fmap #(assoc %
                    :command "replace catalog"
                    :version (Integer/parseInt (-> % :payload :version))
                    :certname (-> % :payload :certname))
            (gen/hash-map :payload (catalog)
                          :received (command-received))))

(defn replace-facts-command []
  (gen/fmap #(assoc %
                    :command "replace facts"
                    :version 5
                    :certname (-> % :payload :certname))
            (gen/hash-map :payload (factset)
                          :received (command-received))))

(defn store-report-command []
  (gen/fmap #(assoc %
                    :command "store report"
                    :version 7
                    :certname (-> % :payload :certname))
            (gen/hash-map :payload (report)
                          :received (command-received))))

(defn command []
  (gen/one-of [(replace-catalog-command)
               (replace-facts-command)
               (store-report-command)]))
