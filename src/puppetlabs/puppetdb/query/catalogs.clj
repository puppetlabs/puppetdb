(ns puppetlabs.puppetdb.query.catalogs
  "Catalog retrieval

   Returns a catalog in the PuppetDB JSON wire format.  For more info, see
   `documentation/api/wire_format/catalog_format.markdown`."
  (:require [puppetlabs.puppetdb.query.resources :as resources]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.catalogs :as cats]
            [schema.core :as s]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.kitchensink.core :as kitchensink]))

(defn get-catalog-info
  "Given a node name, return a map of Puppet catalog information
  for the most recent catalog that we've seen for that node.
  Returns `nil` if no catalogs are found for the node.
  The map contains the following data:
    - `:catalog-version`
    - `:transaction-uuid`, which may be nil"
  [node]
  {:pre  [(string? node)]
   :post [((some-fn nil? map?) %)]}
  (let [query (str "SELECT catalog_version as version, transaction_uuid as \"transaction-uuid\", "
                   "e.name as environment, COALESCE(c.api_version, 1) as api_version,"
                   "producer_timestamp as \"producer-timestamp\""
                   "FROM catalogs c left outer join environments e on c.environment_id = e.id "
                   "WHERE certname = ?")]
    (first (jdbc/query-to-vec query node))))

(defn resource-to-wire-format
  "Given a resource as returned by our resource database query functions,
  munges the resource into a map that complies with our wire format.  This
  basically involves removing extraneous fields (`certname`, the puppetdb resource
  hash), and removing the `file` and `line` fields if they are `nil`."
  [resource]
  {:pre  [(map? resource)]
   :post [(map? %)
          (empty? (select-keys % [:certname :resource]))]}
  (-> resource
      (dissoc :certname :resource :environment)
      ;; All of the sample JSON catalogs I've seen do not include the `file`/`line`
      ;; fields if we don't have actual values for them.
      (kitchensink/dissoc-if-nil :line :file)))

(defn get-resources
  "Given a node name, return a sequence of resources (as maps, conforming to
  our catalog wire format) that appear in the node's latest catalog."
  [version node]
  {:pre  [(string? node)]
   :post [(seq? %)
          (every? map? %)]}
  (map resource-to-wire-format
       (->> (resources/query->sql version ["=" "certname" node])
            (resources/query-resources version)
            (:result))))

(defn get-edges
  "Fetch the edges for the current catalog for the given `node`.  Edges are returned
  as maps, conforming to the catalog wire format."
  [node]
  {:pre  [(string? node)]
   :post [(seq? %)
          (every? map? %)]}
  (let [query (str "SELECT sources.type AS source_type, "
                   "sources.title AS source_title, "
                   "targets.type AS target_type, "
                   "targets.title AS target_title, "
                   "e.type AS relationship "
                   "FROM edges e "
                   "INNER JOIN catalog_resources sources "
                   "ON e.source = sources.resource "
                   "INNER JOIN catalog_resources targets "
                   "ON e.target = targets.resource "
                   "INNER JOIN catalogs c "
                   "ON sources.catalog_id = c.id "
                   "AND targets.catalog_id = c.id "
                   "AND e.certname = c.certname "
                   "WHERE e.certname = ?")]
    (for [{:keys [source_type
                  source_title
                  target_type
                  target_title
                  relationship]} (jdbc/query-to-vec query node)]
      {:source       {:type source_type :title source_title}
       :target       {:type target_type :title target_title}
       :relationship relationship})))

(defn get-full-catalog [catalog-version node]
  (let [{:keys [version transaction-uuid environment api_version producer-timestamp] :as catalog}
        (get-catalog-info node)]
    (when (and catalog-version catalog)
      {:name             node
       :edges            (get-edges node)
       :resources        (get-resources version node)
       :version          version
       :transaction-uuid transaction-uuid
       :environment environment
       :api_version api_version
       :producer-timestamp producer-timestamp})))

(defn catalog-response-schema
  "Returns the correct schema for the `version`, use :all for the full-catalog (superset)"
  [api-version]
  (case api-version
    :v4 (cats/catalog-wireformat :v5)
    (cats/catalog-wireformat api-version)))

(defn validate-api-catalog
  [api-version catalog]
  (case api-version
    :v4 (cats/canonical-catalog :v5 catalog)
   (cats/canonical-catalog api-version catalog)))

(pls/defn-validated validate-api-catalog
  "Converts `catalog` to `version` in the canonical format, adding
   and removing keys as needed"
  [api-version catalog]
  (let [target-schema (catalog-response-schema api-version)
        strip-keys #(pls/strip-unknown-keys target-schema %)]
    (s/validate target-schema
                (case api-version
                  :v1 (-> catalog
                          (dissoc :transaction-uuid :environment :producer-timestamp)
                          cats/old-wire-format-schema)
                  :v2 (-> catalog
                          (dissoc :transaction-uuid :environment :producer-timestamp)
                          cats/old-wire-format-schema
                          strip-keys)
                  :v3 (-> catalog
                          (dissoc :environment :producer-timestamp)
                          cats/old-wire-format-schema
                          strip-keys)
                  :v4 (strip-keys (dissoc catalog :api_version))
                  (strip-keys (dissoc catalog :api_version))))))

(pls/defn-validated catalog-for-node
  "Retrieve the catalog for `node`."
  [version node]
  {:pre  [(string? node)]}
  (when-let [catalog (get-full-catalog version node)]
    (validate-api-catalog version catalog)))
