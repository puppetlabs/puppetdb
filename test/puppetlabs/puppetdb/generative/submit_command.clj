(ns puppetlabs.puppetdb.generative.submit-command
  (:require [puppetlabs.puppetdb.command :as cmd]
            [clojure.test :as t]
            [clojure.test.check.clojure-test :as tc]
            [clojure.test.check.properties :as prop]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command :as command]
            [clj-time.core :as time :refer [now]]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [puppetlabs.puppetdb.generative.overridable-generators :as gen]
            [puppetlabs.puppetdb.generative.generators :as pdb-gen]
            [puppetlabs.puppetdb.examples.reports :as example-reports]
            [puppetlabs.puppetdb.time :refer [parse-wire-datetime]]))

(def ten-timestamps #{"2016-12-19T23:30:00.000Z"
                      "2016-12-19T23:31:00.000Z"
                      "2016-12-19T23:32:00.000Z"
                      "2016-12-19T23:33:00.000Z"
                      "2016-12-19T23:34:00.000Z"
                      "2016-12-19T23:35:00.000Z"
                      "2016-12-19T23:36:00.000Z"
                      "2016-12-19T23:37:00.000Z"
                      "2016-12-19T23:38:00.000Z"
                      "2016-12-19T23:39:00.000Z"})

(def timestamp-gen
  (gen/fmap parse-wire-datetime (gen/elements ten-timestamps)))

(def overrides {:puppet/certname (gen/elements ["a.com" "b.com" "c.com"])
                :catalog/version (gen/return "9")
                :puppet/environment (gen/elements ["production" "development" "test"])
                :puppet/producer_timestamp timestamp-gen
                :command/received timestamp-gen
                :puppet/producer (gen/elements ["a.com" "b.com" "c.com"])
                :catalog/resource-parameters (gen/map (pdb-gen/string+)
                                                      (gen/one-of [(pdb-gen/string+)
                                                                   pdb-gen/pg-smallint
                                                                   gen/boolean])
                                                      {:min-elements 0
                                                       :max-elements 3})})

;;; Utilities for comparing puppetdb states

(defn all-catalogs [db]
  (->> (qeng/stream-query-result :v4
                             ["from" "catalogs"]
                             {:order_by [[:certname :descending]
                                         [:producer_timestamp :descending]]}
                             {:scf-read-db db, :url-prefix "/pdb"})
       (map (fn [row]
              (-> row
                  (update :resources #(json/parse (str % ) true))
                  (update :edges #(json/parse (str % ) true))
                  (update-in [:resources :data] set))))))

(defn all-factsets [db]
  (->> (qeng/stream-query-result :v4
                                 ["from" "factsets"]
                                 {:order_by [[:certname :descending]
                                             [:producer_timestamp :descending]]}
                                 {:scf-read-db db, :url-prefix "/pdb"})
       (map (fn [row]
              (-> row
                  (update :facts #(json/parse (str % ) true))
                  (update-in [:facts :data] #(sort-by :name %)))))))

(defn all-reports [db]
  (->> (qeng/stream-query-result :v4
                                 ["from" "reports"]
                                 {:order_by [[:certname :descending]
                                             [:producer_timestamp :descending]]}
                                 {:scf-read-db db, :url-prefix "/pdb"})
       (map (fn [row]
              (-> row
                  (update :logs #(json/parse (str %) true))
                  (update :metrics #(json/parse (str %) true))
                  (update :resource_events #(json/parse (str %) true))
                  (update-in [:resource_events :data] #(into #{} %)))))))

(defn all-nodes [db]
  (qeng/stream-query-result :v4
                            ["from" "nodes"]
                            {:order_by [[:certname :descending]]}
                            {:scf-read-db db, :url-prefix "/pdb"}) )

(defn state-snapshot [db]
  {:nodes (all-nodes db)
   :catalogs (all-catalogs db)
   :factsets (all-factsets db)
   :reports (all-reports db)})

;;; Generative tests

(tc/defspec submit-commands 25
  (prop/for-all
   [cmd (->> :puppetdb/command
             (gen/override overrides)
             gen/no-shrink
             gen/convert)]
   (with-test-db
     (t/is (= nil (cmd/process-command! cmd *db* nil))))))

(tc/defspec same-command-produces-equal-snapshots 25
  (prop/for-all
   [cmd (->> :puppetdb/command
             (gen/override overrides)
             gen/no-shrink
             gen/convert)]
   (let [state-1 (with-test-db
                   (cmd/process-command! cmd *db* nil)
                   (state-snapshot *db*))
         state-2 (with-test-db
                   (cmd/process-command! cmd *db* nil)
                   (state-snapshot *db*))]
     (t/is (= state-1 state-2)))))

(defn check-commands-commute [cmd1 cmd2]
  (let [state-1 (with-test-db
                  (cmd/process-command! cmd1 *db* nil)
                  (cmd/process-command! cmd2 *db* nil)
                  (state-snapshot *db*))
        state-2 (with-test-db
                  (cmd/process-command! cmd2 *db* nil)
                  (cmd/process-command! cmd1 *db* nil)
                  (state-snapshot *db*))]
    (t/is (= state-1 state-2))))

(tc/defspec commands-are-commutative 25
  (prop/for-all
   ;; we know that commands must be unique by certname + producer-timestamp to commute
   [commands (gen/convert (gen/vector-distinct-by (juxt :certname (comp :producer_timestamp :payload))
                                                  (gen/no-shrink (gen/override overrides
                                                                               :puppetdb/command))
                                                  {:num-elements 2}))]
   (apply check-commands-commute commands)))

;;; Regression tests for specific issues found via generative tests
(t/deftest factsets-with-different-producers-commute-test
  (check-commands-commute
   {:payload {:certname "b.com", :environment "production", :producer_timestamp "2016-12-19T23:38:00.000Z", :producer "b.com", :values {"b", 43}}, :received (now), :command "replace facts", :version 5, :certname "b.com"}
   {:payload {:certname "b.com", :environment "production", :producer_timestamp "2016-12-19T23:32:00.000Z", :producer "c.com", :values {"a", 42}}, :received (now), :command "replace facts", :version 5, :certname "b.com"}))

(t/deftest reports-with-different-producer-timestamps-commute-test
  (check-commands-commute
   {:payload (assoc example-reports/v8-report :producer_timestamp "2016-12-19T23:38:00.000Z"), :received (now), :command "store report", :version 8, :certname "b.com"}
   {:payload (assoc example-reports/v8-report :producer_timestamp "2016-12-19T23:32:00.000Z"), :received (now), :command "store report", :version 8, :certname "b.com"}))
