(ns com.puppetlabs.puppetdb.scf.hash
  (:require [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.utils :as utils]))

(defn generic-identity-string
  "Serialize a data structure into a format that can be hashed for uniqueness
  comparisons. See `generic-identity-hash` for a usage that generates a hash
  instead."
  [data]
  {:post [(string? %)]}
  (-> (utils/sort-nested-maps data)
      (json/generate-string)))

(defn generic-identity-hash
  "Convert a data structure into a serialized format then grab a sha1 hash for
  it so that can be used for quick comparisons for storage duplication tests."
  [data]
  {:post [(string? %)]}
  (-> data
    (generic-identity-string)
    (utils/utf8-string->sha1)))

(defn resource-identity-hash*
  "Compute a hash for a given resource that will uniquely identify it
  _for storage deduplication only_.

  A resource is represented by a map that itself contains maps and
  sets in addition to scalar values. We want two resources with the
  same attributes to be equal for the purpose of deduping, therefore
  we need to make sure that when generating a hash for a resource we
  look at a stably-sorted view of the resource. Thus, we need to sort
  both the resource as a whole as well as any nested collections it
  contains.

  This differs from `catalog-resource-identity-string` in that it
  doesn't consider resource metadata. This function is used to
  determine whether a resource needs to be stored or is already
  present in the database.

  See `resource-identity-hash`. This variant takes specific attribute
  of the resource as parameters, whereas `resource-identity-hash`
  takes a full resource as a parameter. By taking only the minimum
  required parameters, this function becomes amenable to more efficient
  memoization."
  [type title parameters]
  {:post [(string? %)]}
  (generic-identity-hash [type title parameters]))

;; Size of the cache is based on the number of unique resources in a
;; "medium" site persona
(def resource-identity-hash* (utils/bounded-memoize resource-identity-hash* 40000))

(defn resource-identity-hash
  "Compute a hash for a given resource that will uniquely identify it
  _for storage deduplication only_.

  See `resource-identity-hash*`. This variant takes a full resource as
  a parameter, whereas `resource-identity-hash*` takes specific
  attribute of the resource as parameters."
  [{:keys [type title parameters] :as resource}]
  {:pre  [(map? resource)]
   :post [(string? %)]}
  (resource-identity-hash* type title parameters))

(defn catalog-resource-identity-string
  "Compute a stably-sorted, string representation of the given
  resource that will uniquely identify it with respect to a
  catalog. Unlike `resource-identity-hash`, this string will also
  include the resource metadata. This function is used as part of
  determining whether a catalog needs to be stored."
  [{:keys [type title parameters exported file line] :as resource}]
  {:pre  [(map? resource)]
   :post [(string? %)]}
  (generic-identity-string [type title exported file line parameters]))

(defn edge-identity-string
  "Compute a string for a given edge that will uniquely identify it
  within a population."
  [edge]
  {:pre  [(map? edge)]
   :post [(string? %)]}
  (generic-identity-string edge))

(defn catalog-similarity-hash
  "Compute a hash for the given catalog's content

  This hash is useful for situations where you'd like to determine
  whether or not two catalogs contain the same things (edges,
  resources, etc).

  Note that this hash *cannot* be used to uniquely identify a catalog
  within a population! This is because we're only examing a subset of
  a catalog's attributes. For example, two otherwise identical
  catalogs with different :version's would have the same similarity
  hash, but don't represent the same catalog across time."
  [{:keys [certname resources edges] :as catalog}]
  {:post [(string? %)]}
  ;; deepak: This could probably be coded more compactly by just
  ;; dissociating the keys we don't want involved in the computation,
  ;; but I figure that for safety's sake, it's better to be very
  ;; explicit about the exact attributes of a catalog that we care
  ;; about when we think about "uniqueness".
  (generic-identity-hash {:certname  certname
                          :resources (sort (for [[ref resource] resources]
                                             (catalog-resource-identity-string resource)))
                          :edges     (sort (map edge-identity-string edges))}))

(defn resource-event-identity-string
  "Compute a string suitable for hashing a resource event

  This hash is useful for situations where you'd like to determine
  whether or not two resource events are identical (resource type, resource title,
  property, values, status, timestamp, etc.)
  "
  [{:keys [resource-type resource-title property timestamp status old-value
           new-value message file line] :as resource-event}]
  (generic-identity-string
    { :resource-type resource-type
      :resource-title resource-title
      :property property
      :timestamp timestamp
      :status status
      :old-value old-value
      :new-value new-value
      :message message
      :file file
      :line line}))

(defn report-identity-hash
  "Compute a hash for a report's content

  This hash is useful for situations where you'd like to determine
  whether or not two reports contain the same things (certname,
  configuration version, timestamps, events).
  "
  [{:keys [certname puppet-version report-format configuration-version
           start-time end-time resource-events transaction-uuid] :as report}]
  (generic-identity-hash
    {:certname certname
     :puppet-version puppet-version
     :report-format report-format
     :configuration-version configuration-version
     :start-time start-time
     :end-time end-time
     :resource-events (sort (map resource-event-identity-string resource-events))
     :transaction-uuid transaction-uuid}))
