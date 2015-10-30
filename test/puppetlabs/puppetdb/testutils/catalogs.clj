(ns puppetlabs.puppetdb.testutils.catalogs
  (:require [puppetlabs.puppetdb.command :as command]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.catalogs :as cats]
            [puppetlabs.kitchensink.core :refer [uuid]]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [schema.core :as s]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.catalogs :refer [catalog-version]]))

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
    :version     catalog-version}
   *db*))

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
          (set? (get % "resources"))
          (set? (get % "edges"))
          (string? (get % "version"))]}
  (-> catalog
      clojure.walk/stringify-keys
      (dissoc "hash")
      (update "producer_timestamp" to-string)
      (update "resources" (fn [resources] (set (map #(update % "tags" set) resources))))
      (update "edges" (fn [edges] (set (map #(update % "relationship" name) edges))))
      ;; In our terminus code, the version is sometimes being serialized as a JSON
      ;;  integer, rather than a string.  The correct data type is String.
      (update "version" str)))

(defn munge-catalog
  "Munges a catalog or list of catalogs for comparison.
   Returns a list of catalogs."
  [catalog-or-catalogs]
  (->> catalog-or-catalogs
       utils/vector-maybe
       (map munge-catalog-for-comparison)))
