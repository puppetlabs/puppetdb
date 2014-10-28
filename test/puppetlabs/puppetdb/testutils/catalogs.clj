(ns puppetlabs.puppetdb.testutils.catalogs
  (:require [puppetlabs.puppetdb.command :as command]
            [cheshire.core :as json]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.catalogs :as cats]
            [puppetlabs.kitchensink.core :refer [uuid]]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.testutils :refer [test-db]]
            [schema.core :as s]
            [puppetlabs.puppetdb.fixtures :refer [*db*]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.catalogs :refer [catalog-version]]))

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

(defn update-in*
  "Similar to update in, but removes any nil keys (`ks`) before
   calling update-in"
  [m ks f & args]
  (apply update-in m (remove nil? ks) f args))

(defn canonical->wire-format
  "Converts the `catalog` in the canonical format to the correct wire-format version.
   Note that the wire format is still in keywords"
  [version catalog]
  (let [versioned-catalog (->> catalog
                               (cats/canonical-catalog :all)
                               (cats/canonical-catalog version))]
    (s/validate (cats/catalog-wireformat version) versioned-catalog)))

(defn munge-catalog-for-comparison* [catalog-root-key catalog]
  (-> catalog
      (clojure.walk/stringify-keys)
      (update-in* [catalog-root-key "resources"] #(map munge-resource-for-comparison %))
      (update-in* [catalog-root-key "resources"] set)
      (update-in* [catalog-root-key "edges"] (fn [edges]
                                               (set (map #(update-in % ["relationship"] name) edges))))
      ;; In our terminus code, the version is sometimes being serialized as a JSON
      ;;  integer, rather than a string.  The correct data type is String.
      (update-in* [catalog-root-key "version"] str)))

(defn munge-v3-catalog
  "Uses a 'data' prefix path for getting to resources/edges etc needed for munging
   a v3 catalog. Eventually this should be moved to support our canonical format, then
   we could just convert to 'all' to compare the superset, or test comparison of each version
   (taking into account missing keys etc)"
  [catalog]
  {:pre  [(map? catalog)]
   :post [(map? %)
          (every? string? (keys %))
          (set? (get-in % ["data" "resources"]))
          (set? (get-in % ["data" "edges"]))
          (string? (get-in % ["data" "version"]))]}
  (munge-catalog-for-comparison* "data" catalog))

(defn munge-v4-catalog
  "Uses a 'nil' prefix path for getting to resources/edges etc needed for munging
   a v4 catalog. Eventually this should be moved to support our canonical format, then
   we could just convert to 'all' for example, munge and compare the superset, and it
   would work for all versions."
  [catalog]
  {:pre  [(map? catalog)]
   :post [(map? %)
          (every? string? (keys %))
          (set? (get-in % ["resources"]))
          (set? (get-in % ["edges"]))
          (string? (get-in % ["version"]))]}
  (munge-catalog-for-comparison* nil catalog))

(defn munge-v5-catalog
  "Uses a 'nil' prefix path for getting to resources/edges etc needed for munging
   a v5 catalog. Eventually this should be moved to support our canonical format, then
   we could just convert to 'all' for example, munge and compare the superset, and it
   would work for all versions."
  [catalog]
  {:pre  [(map? catalog)]
   :post [(map? %)
          (every? string? (keys %))
          (set? (get-in % ["resources"]))
          (set? (get-in % ["edges"]))
          (string? (get-in % ["version"]))]}
  (munge-catalog-for-comparison* nil (update-in catalog [:producer-timestamp] to-string)))

(defn munge-catalog-for-comparison
  "Given a catalog object (represented as a map, either having come out of a
  puppetdb database query or parsed from the JSON wire format), munge it and
  return a version that will be suitable for equality comparison in tests.  This
  mostly entails mapping certain fields that would be represented as JSON arrays--
  but whose ordering is not actually relevant for equality testing--to sets (which
  JSON doesn't have a data type for)."
  [version catalog]
  {:pre  [(map? catalog)]}
  (case version
    :v3 (munge-v3-catalog catalog)
    :v4 (munge-v4-catalog catalog)
    (munge-v5-catalog catalog)))

(defn munged-canonical->wire-format
  "Converts the given canonical catalog to wire-format `version` then
   stringifies the keys and munges the catalog"
  [version catalog]
  (->> catalog
       (canonical->wire-format version)
       (munge-catalog-for-comparison version)))

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
   {:db          *db*}))
