(ns puppetlabs.puppetdb.testutils.cli
  (:require [clj-time.coerce :as time-coerce]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
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
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.time :refer [now]]
            [clojure.walk :as walk]))

(defn get-nodes []
  (-> (svc-utils/query-url-str "/nodes")
      svc-utils/get-or-throw
      :body))

(defn get-catalogs [certname]
  (-> (svc-utils/get-catalogs certname)
      catalogs/catalogs-query->wire-v9
      vec))

(defn get-reports [certname]
  (-> (svc-utils/get-reports certname)
      tur/munge-reports
      reports/reports-query->wire-v8
      vec))

(defn get-factsets [certname]
  (-> (svc-utils/get-factsets certname)
      factsets/factsets-query->wire-v5
      vec))

(defn get-summary-stats []
  (svc-utils/get-summary-stats))

(def example-certname "foo.local")

(def example-producer "bar.com")

(def example-facts
  {:certname example-certname
   :environment "DEV"
   :values {:foo "the foo"
            :bar "the bar"
            :baz "the baz"
            :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}
   :producer_timestamp (time-coerce/to-string (now))
   :producer example-producer})

(def example-catalog
  (-> examples/wire-catalogs
      (get-in [9 :empty])
      (assoc :certname example-certname
             :producer_timestamp (now))))

(def example-report
  (-> examples-reports/reports
      :basic
      (assoc :certname example-certname)
      tur/munge-report
      reports/report-query->wire-v8))

(defn munge-tar-map
  [tar-map]
  (-> tar-map
      (dissoc "export-metadata.json")
      (update "facts" #(kitchensink/mapvals tuf/munge-facts %))
      (update "reports" #(kitchensink/mapvals (comp stringify-keys
                                                    tur/munge-report-for-comparison
                                                    keywordize-keys) %))
      (update "catalogs" #(kitchensink/mapvals tuc/munge-catalog %))))
