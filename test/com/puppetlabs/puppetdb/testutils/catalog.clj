(ns com.puppetlabs.puppetdb.testutils.catalog
  (:require [com.puppetlabs.puppetdb.command :as command]
            [cheshire.core :as json])
  (:use     [com.puppetlabs.utils :only [uuid]]
            [clj-time.core :only [now]]
            [com.puppetlabs.puppetdb.testutils :only [test-db]]
            [com.puppetlabs.puppetdb.fixtures :only [*db*]]
            [com.puppetlabs.puppetdb.command.constants :only [command-names]]))


(defn munge-resource-for-comparison
  "Given a resource object (represented as a map, either having come out of a
  puppetdb database query or parsed from the JSON wire format), munge it and
  return a version that will be suitable for equality comparison in tests."
  [resource]
  {:pre  [(map? resource)]
   :post [(map? %)
          (set? (% "tags"))]}
  ;; we don't care about the order of the tags, so we can safely convert from
  ;; an ordered list to a set.
  (update-in resource ["tags"] set))


(defn munge-catalog-for-comparison
  "Given a catalog object (represented as a map, either having come out of a
  puppetdb database query or parsed from the JSON wire format), munge it and
  return a version that will be suitable for equality comparison in tests.  This
  mostly entails mapping certain fields that would be represented as JSON arrays--
  but whose ordering is not actually relevant for equality testing--to sets (which
  JSON doesn't have a data type for)."
  [catalog]
  {:pre  [(map? catalog)]
   :post [(map? %)
          (every? string? (keys %))
          (set? (get-in % ["data" "resources"]))
          (set? (get-in % ["data" "edges"]))
          (string? (get-in % ["data" "version"]))]}
  (-> catalog
    (clojure.walk/stringify-keys)
    (update-in ["data" "resources"] #(map munge-resource-for-comparison %))
    (update-in ["data" "resources"] set)
    (update-in ["data" "edges"] set)
    ;; In our terminus code, the version is sometimes being serialized as a JSON
    ;;  integer, rather than a string.  The correct data type is String.
    (update-in ["data" "version"] str)))

(defn replace-catalog
  "Convenience function for simulating a `replace catalog` command during testing.

  Accepts a catalog payload string (in exactly the format that the command accepts),
  and synchronously executes the logic that the command would (without needing
  to drag ActiveMQ into the test stack)."
  [catalog-payload]
  (command/process-command!
    {:command     (command-names :replace-catalog)
     :payload     catalog-payload
     :annotations {:id (uuid)
                   :received (now)}
     :version     2}
    {:db          *db*}))
