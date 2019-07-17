(ns puppetlabs.puppetdb.scf.hash
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.package-util :refer [package-tuple hashed-package-tuple
                                                      package-tuple-hash]]
            [schema.core :as s])
  (:import [java.security MessageDigest]))

(defn generic-identity-string
  "Serialize a data structure into a format that can be hashed for uniqueness
  comparisons. See `generic-identity-hash` for a usage that generates a hash
  instead."
  [data]
  {:post [(string? %)]}
  (-> (kitchensink/sort-nested-maps data)
      (json/generate-string)))

(defn generic-identity-hash
  "Convert a data structure into a serialized format then grab a sha1 hash for
  it so that can be used for quick comparisons for storage duplication tests."
  [data]
  {:post [(string? %)]}
  (-> data
      (generic-identity-string)
      (kitchensink/utf8-string->sha1)))

(def ^{:doc "Returns true if x is a 20 byte array."}
  sha1-bytes?
  (let [c (class (byte-array []))]
    (fn [x]
      (and (instance? c x)
           (= 20 (count x))))))

(defn generic-identity-sha1-bytes
  "Returns a SHA1 fingerprint for the provided data structure as a 20
  byte array."
  [data]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (.update digest (-> data generic-identity-string (.getBytes "UTF-8")))
    (.digest digest)))

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

(defn catalog-resource-identity-format
  "Narrow `resource` to only contain the needed key/values for computing the hash of
   the given resource."
  [resource]
  {:pre  [(map? resource)]
   :post [(map? %)]}
  (select-keys resource [:type :title :parameters :exported :file :line :tags]))

(defn resource-comparator
  "Compares two resources by the title and type.  Useful in calls to sort to
   get a stable ordering of resources."
  [{lhs-title :title :as lhs} {rhs-title :title :as rhs}]
  (let [cmp-val (compare lhs-title rhs-title)]
    (if (zero? cmp-val)
      (compare (:type lhs) (:type rhs))
      cmp-val)))

(defn edge-comparator
  "Function used for comparing two edges by their source/target resources, suitable
   for the clojure sort function."
  [{lhs-source :source :as lhs} {rhs-source :source :as rhs}]
  (let [source-result (resource-comparator lhs-source rhs-source)]
    (if (zero? source-result)
      (let [target-result (resource-comparator (:target lhs) (:target rhs))]
        (if (zero? target-result)
          (compare (:relationship lhs) (:relationship rhs))
          target-result))
      source-result)))

(s/defn package-similarity-hash :- s/Str
  "Creates a stable ordering of `packages` and computes a hash over
  that structure"
  [packages :- [hashed-package-tuple]]
  (->> packages
       (map package-tuple-hash)
       sort
       json/generate-string
       kitchensink/utf8-string->sha1))

(s/defn package-identity-hash :- hashed-package-tuple
  [package :- package-tuple]
  (conj package
        (-> package
            json/generate-string
            kitchensink/utf8-string->sha1)))

(defn catalog-similarity-format
  "Creates catalog map for the given `certname`, `resources` and `edges` with a
   stable ordering that can be used to create a hash consistently."
  [certname resources edges]
  (kitchensink/sort-nested-maps
   {:certname  certname
    :resources (sort resource-comparator (map catalog-resource-identity-format resources))
    :edges     (sort edge-comparator edges)}))

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
  (generic-identity-hash
   (catalog-similarity-format certname (vals resources) edges)))

(defn resource-event-identity-string
  "Compute a string suitable for hashing a resource event

  This hash is useful for situations where you'd like to determine
  whether or not two resource events are identical (resource type, resource title,
  property, values, status, timestamp, etc.)
  "
  [{:keys [resource_type resource_title property timestamp status old_value
           new_value message file line] :as resource_event}]
  (generic-identity-string
   {:resource_type resource_type
    :resource_title resource_title
    :property property
    :timestamp timestamp
    :status status
    :old_value old_value
    :new_value new_value
    :message message
    :file file
    :line line}))

(defn report-identity-hash
  "Compute a hash for a report's content

  This hash is useful for situations where you'd like to determine
  whether or not two reports contain the same things (certname,
  configuration version, timestamps, events)."
  [{:keys [certname puppet_version report_format configuration_version
           start_time end_time producer_timestamp resource_events transaction_uuid] :as report}]
  (generic-identity-hash
   {:certname certname
    :puppet_version puppet_version
    :report_format report_format
    :configuration_version configuration_version
    :start_time start_time
    :end_time end_time
    :producer_timestamp producer_timestamp
    :resource_events (sort (map resource-event-identity-string resource_events))
    :transaction_uuid transaction_uuid}))

(defn fact-identity-hash
  "Compute a hash for a fact's content

  This hash is useful for situations where you'd like to determine
  whether or not two sets of facts are identical, excluding timestamps
  and producer.
  "
  [fact-data]
  (-> ;; If the :package_inventory is not a seq (ie. empty) in fact-data
      ;; we remove package-inventory key entirely because an absent
      ;; and empty package inventory are stored identically so there's no
      ;; way to tell them apart during sync, and we need the the hashing
      ;; to be consistent.
      (if (seq (:package_inventory fact-data))
        fact-data
        (dissoc fact-data :package_inventory))
      (dissoc :timestamp :producer_timestamp :producer)
      generic-identity-hash))
