(ns puppetlabs.puppetdb.testutils.cli
  (:require [clj-time.coerce :as time-coerce]
            [clj-time.core :as time]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.examples.reports :as examples-reports]
            [puppetlabs.puppetdb.factsets :as factsets]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(defn get-child [href]
  (svc-utils/get-json (svc-utils/pdb-query-url)
                      (subs href (count "/pdb/query/v4"))))

(defn get-children [entities fields]
  (map (partial kitchensink/mapvals
                (fn [child]
                  (utils/assoc-when child :data (get-child (:href child))))
                fields)
       entities))

(defn get-nodes []
  (svc-utils/get-json (svc-utils/pdb-query-url) "/nodes"))

(defn get-catalogs [certname]
  (-> (svc-utils/pdb-query-url)
      (svc-utils/get-catalogs certname)
      (get-children [:edges :resources])
      catalogs/catalogs-query->wire-v6
      vec))

(defn get-reports [certname]
  (-> (svc-utils/pdb-query-url)
      (svc-utils/get-reports certname)
      (get-children [:metrics :logs :resource_events])
      reports/reports-query->wire-v5
      vec))

(defn get-factsets [certname]
  (-> (svc-utils/pdb-query-url)
      (svc-utils/get-factsets certname)
      (get-children [:facts])
      factsets/factsets-query->wire-v4
      vec))

(def example-certname "foo.local")

(def example-facts
  {:certname example-certname
   :environment "DEV"
   :values {:foo "the foo"
            :bar "the bar"
            :baz "the baz"
            :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}
   :producer_timestamp (time-coerce/to-string (time/now))})

(def example-catalog
  (-> examples/wire-catalogs
      (get-in [6 :empty])
      (assoc :certname example-certname
             :producer_timestamp (time/now))))

(def example-report
  (-> examples-reports/reports
      :basic
      (assoc :certname example-certname)
      tur/munge-example-report-for-storage))

(defn munge-tar-map
  [tar-map]
  (-> tar-map
      (dissoc "export-metadata.json")
      (update "facts" tuf/munge-facts)
      (update "reports" tur/munge-report)
      (update "catalogs" tuc/munge-catalog)))
