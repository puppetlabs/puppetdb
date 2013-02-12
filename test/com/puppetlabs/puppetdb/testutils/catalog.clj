(ns com.puppetlabs.puppetdb.testutils.catalog
  (:require [com.puppetlabs.puppetdb.command :as command]
            [cheshire.core :as json])
  (:use     [com.puppetlabs.utils :only [uuid]]
            [clj-time.core :only [now]]
            [com.puppetlabs.puppetdb.testutils :only [test-db]]))


(defn munge-resource-for-comparison
  "Given a resource object (represented as a map, either having come out of a
  puppetdb database query or parsed from the JSON wire format), munge it and
  return a version that will be suitable for equality comparison in tests.  This
  mostly entails mapping certain fields that would be represented as JSON arrays--
  but whose ordering is not actually relevant for equality testing--to sets (which
  JSON doesn't have a data type for)."
  [resource]
  {:pre  [(map? resource)]
   :post [(map? %)
          (set? (% "tags"))]}
  (-> resource
    ;; we don't care about the order of the tags, so we can safely convert from
    ;; an ordered list to a set.
    (update-in ["tags"] set)))

(defn ensure-json
  "Convenience function for ensuring that a catalog map is in a JSON-friendly
  format.  When we retrieve catalogs directly from the database, the keys of the
  map will be clojure `keyword` objects.  These are not representable in JSON,
  so whenever we want to do any catalog comparisons for tests, we need to make
  sure that all the keys have been converted to Strings.

  This function accepts a catalog, checks to see if it looks like it's already
  been converted to the JSON-friendly representation, and if not, does so."
  [catalog]
  {:pre  [(map? catalog)]
   :post [(map? %)
          (every? string? (keys %))]}
  (if (some keyword? (keys catalog))
    (json/parse-string
      (json/generate-string
        catalog))
    catalog))


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
    (ensure-json)
    (update-in ["data" "resources"] #(map munge-resource-for-comparison %))
    (update-in ["data" "resources"] set)
    (update-in ["data" "edges"] set)
    ;; TODO: doc
    (update-in ["data" "version"] str)))

(defn replace-catalog
  "Convience function for simulating a `replace catalog` command during testing.

  Accepts a catalog payload string (in exactly the format that the command accepts),
  and synchronously executes the logic that the command would (without needing
  to drag ActiveMQ into the test stack)."
  [catalog-payload]
  ;; This is kind of nasty, but it was the best approximation of an import
  ;; that I could come up with w/o having to drag ActiveMQ into the test
  (command/replace-catalog*
    {:payload     catalog-payload
     :annotations {:id (uuid)
                   :received (now)}
     :version     2}
    {:db          (test-db)}))
