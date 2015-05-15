(ns puppetlabs.puppetdb.query.facts
  "Fact query generation"
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

;; MUNGE

(defn munge-result-rows
  [_ _]
  (partial map #(utils/update-when % [:value] json/parse-string)))

(defn munge-path-result-rows
  [_ _]
  (partial map #(utils/update-when % [:path] facts/string-to-factpath)))

;; QUERY

(defn fact-paths-query->sql
  [version query paging-options]
  (qe/compile-user-query->sql qe/fact-paths-query query paging-options))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (let [columns (->> (dissoc query/fact-columns "value")
                     keys
                     (map keyword))]
    (paging/validate-order-by! columns paging-options)
    (qe/compile-user-query->sql qe/facts-query query paging-options)))

;; QUERY + MUNGE

(defn fact-names
  "Returns the distinct list of known fact names, ordered alphabetically
  ascending. This includes facts which are known only for deactivated and
  expired nodes."
  ([] (fact-names {}))
  ([paging-options]
   {:post [(map? %)
           (coll? (:result %))
           (every? string? (:result %))]}
   (paging/validate-order-by! [:name] paging-options)
   (-> "SELECT DISTINCT name FROM fact_paths"
       (str (when-not (:order_by paging-options) " ORDER BY name"))
       vector
       (query/execute-query paging-options)
       (update-in [:result] (partial map :name)))))
