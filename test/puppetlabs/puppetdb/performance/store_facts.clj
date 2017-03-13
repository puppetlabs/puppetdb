(ns puppetlabs.puppetdb.performance.store-facts
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.command :as cmd]
            [clj-time.core :as t]
            [puppetlabs.puppetdb.performance.utils :as pu]))

(defn ^:performance single-static-fact []
  (pu/simple-db-perf-test
   {:warmup 500
    :samples 500
    :improve-percent 10
    :regress-percent 5}
   (fn [trial-num db]
     (cmd/replace-facts* {:id trial-num
                          :received "2015-12-01"
                          :payload {:certname (str "host-" trial-num)
                                    :values {"foo" "bar"}
                                    :environment "production"
                                    :timestamp "2015-12-01"
                                    :producer_timestamp "2015-12-01"
                                    :producer "master"}}
                         (t/now)
                         db))))

(defn ^:performance fact-update []
  (pu/simple-db-perf-test
   {:warmup 500
    :samples 500
    :improve-percent 10
    :regress-percent 5}
   (fn [trial-num db]
     (cmd/replace-facts* {:id (* 2 trial-num)
                          :received "2015-12-01"
                          :payload {:certname (str "host-" trial-num)
                                    :values {"foo" "bar"}
                                    :environment "production"
                                    :timestamp "2015-12-01"
                                    :producer_timestamp "2015-12-01"
                                    :producer "master"}}
                         (t/now)
                         db)

     (cmd/replace-facts* {:id (inc (* 2 trial-num))
                          :received "2015-12-02"
                          :payload {:certname (str "host-" trial-num)
                                    :values {"foo" "baz"}
                                    :environment "production"
                                    :timestamp "2015-12-02"
                                    :producer_timestamp "2015-12-02"
                                    :producer "master"}}
                         (t/now)
                         db))))

(defn ^:performance prepend-to-array []
  (pu/simple-db-perf-test
   {:warmup 500
    :samples 500
    :improve-percent 10
    :regress-percent 5}
   (fn [trial-num db]
     (cmd/replace-facts* {:id (dec (* 2 trial-num))
                          :received "2015-12-01"
                          :payload {:certname (str "host-" trial-num)
                                    :values {"foo" [2 3 4]}
                                    :environment "production"
                                    :timestamp "2015-12-01"
                                    :producer_timestamp "2015-12-01"
                                    :producer "master"}}
                         (t/now)
                         db)

     (cmd/replace-facts* {:id (* 2 trial-num)
                          :received "2015-12-02"
                          :payload {:certname (str "host-" trial-num)
                                    :values {"foo" [1 2 3 4]}
                                    :environment "production"
                                    :timestamp "2015-12-02"
                                    :producer_timestamp "2015-12-02"
                                    :producer "master"}}
                         (t/now)
                         db))))

