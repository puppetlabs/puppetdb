(ns com.puppetlabs.cmdb.query.population
  (:use [com.puppetlabs.jdbc :only [query-to-vec]]))

(defn correlate-exported-resources
  "Fetch a map of {exported-resource [#{exporting-nodes} #{collecting-nodes}]},
  to correlate the nodes exporting and collecting resources."
  []
  (query-to-vec (str "SELECT DISTINCT exporters.type, exporters.title, "
                     "(SELECT certname FROM certname_catalogs WHERE catalog=exporters.catalog) AS exporter, "
                     "(SELECT certname FROM certname_catalogs WHERE catalog=collectors.catalog) AS collector "
                     "FROM catalog_resources exporters, catalog_resources collectors "
                     "WHERE exporters.resource=collectors.resource AND exporters.exported=true AND collectors.exported=false "
                     "ORDER BY exporters.type, exporters.title, exporter, collector ASC")))
