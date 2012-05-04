;; ## Status query
;;
;; ### Node status
;;
;; Node status information will be returned in the form:
;;
;;     {:name <node>
;;      :deactivated <timestamp>
;;      :catalog_timestamp <timestamp>
;;      :facts_timestamp <timestamp>}
;;
;; If the node is active, "deactivated" will be null. If a catalog or facts are
;; not present, the corresponding timestamps will be null.
(ns com.puppetlabs.puppetdb.query.status
  (:use [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.utils :only [keyset]]))

(defn node-status
  "Return the current status of a node, including whether it's active, and the
  timestamp of its most recent catalog and facts."
  [node]
  {:post [(or (nil? %)
              (= #{:name :deactivated :catalog_timestamp :facts_timestamp} (keyset %)))]}
  (let [results (query-to-vec (str "SELECT certnames.name, certnames.deactivated, c.timestamp AS catalog_timestamp, f.timestamp AS facts_timestamp "
                                   "FROM certnames LEFT OUTER JOIN certname_catalogs c ON certnames.name = c.certname "
                                   "LEFT OUTER JOIN certname_facts_metadata f ON certnames.name = f.certname "
                                   "WHERE certnames.name = ?")
                              node)]
    (first results)))
