;; ## Catalog retrieval
;;
;; Returns a catalog in the PuppetDB JSON wire format.  For more info, see
;;  `documentation/api/wire_format/catalog_format.markdown`.
;;

(ns com.puppetlabs.puppetdb.query.catalog
  (:require [com.puppetlabs.puppetdb.query.resource :as r])
  (:use [com.puppetlabs.jdbc :only [query-to-vec underscores->dashes]]
        [com.puppetlabs.utils :only [dissoc-if-nil mapkeys]]))

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
  (let [query (str "SELECT catalog_version, transaction_uuid "
               "FROM catalogs "
               "INNER JOIN certname_catalogs "
               "ON certname_catalogs.catalog = catalogs.hash "
               "WHERE certname = ?")]
    (mapkeys underscores->dashes (first (query-to-vec query node)))))

(defn resource-to-wire-format
  "Given a resource as returned by our resource database query functions,
  munges the resource into a map that complies with our wire format.  This
  basically involves removing extraneous fields (`certname`, the puppetdb resource
  hash), and removing the `file` and `line` fields if they are `nil`."
  [{:keys [sourceline sourcefile] :as resource}]
  {:pre  [(map? resource)]
   :post [(map? %)
          (empty? (select-keys % [:sourceline :sourcefile :certname :resource]))]}
  (-> resource
    (dissoc :certname :resource :sourceline :sourcefile)
    ;; All of the sample JSON catalogs I've seen do not include the `file`/`line`
    ;; fields if we don't have actual values for them.
    (assoc :line (:sourceline resource) :file (:sourcefile resource))
    (dissoc-if-nil :line :file)))

(defn get-resources
  "Given a node name, return a sequence of resources (as maps, conforming to
  our catalog wire format) that appear in the node's latest catalog."
  [node]
  {:pre  [(string? node)]
   :post [(seq? %)
          (every? map? %)]}
  (map resource-to-wire-format
    (-> ["=" "certname" node]
      (r/v2-query->sql)
      (r/query-resources)
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
                "edges.type AS relationship "
                "FROM certname_catalogs INNER JOIN edges ON certname_catalogs.catalog = edges.catalog "
                "INNER JOIN catalog_resources sources "
                    "ON edges.catalog = sources.catalog "
                    "AND source = sources.resource "
                "INNER JOIN catalog_resources targets "
                    "ON edges.catalog = targets.catalog "
                    "AND target = targets.resource "
                "WHERE certname = ?")]
    (for [{:keys [source_type
                  source_title
                  target_type
                  target_title
                  relationship]} (query-to-vec query node)]
      {:source       {:type source_type :title source_title}
       :target       {:type target_type :title target_title}
       :relationship relationship})))

(defn catalog-for-node
  "Retrieve the catalog for `node`."
  [node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (and (map? %)
                   (= node  (:name (:data %)))
                   (seq?    (:resources (:data %)))
                   (seq?    (:edges (:data %)))
                   (map?    (:metadata %))))]
   }
  ;; the main use of get-catalog-info here is just to do a quick check to
  ;; see if we actually have a catalog for the node
  (let [info (get-catalog-info node)]
    (when-let [catalog-version (:catalog-version info)]
      { :data          {:name             node
                        :edges            (get-edges node)
                        :resources        (get-resources node)
                        :version          catalog-version
                        :transaction-uuid (:transaction-uuid info)}
        :metadata      {:api_version 1 }})))
