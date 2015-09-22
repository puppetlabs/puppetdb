(ns puppetlabs.puppetdb.cli.benchmark-test
  (:require [puppetlabs.puppetdb.cli.benchmark :as benchmark]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [slingshot.test]
            [clojure.core.async :refer [<!! timeout close!]]))


(defn mock-submit-record-fn [submitted-records entity]
  (fn [base-url version payload-string]
    (swap! submitted-records conj
           {:entity entity
            :base-url base-url
            :version version
            :payload-string payload-string
            :payload (json/parse-string payload-string true)})))

(defn run-benchmark [config & cli-args]
  (let [submitted-records (atom [])]
    (with-redefs [client/submit-catalog (mock-submit-record-fn submitted-records :catalog)
                  client/submit-report (mock-submit-record-fn submitted-records :report)
                  client/submit-facts (mock-submit-record-fn submitted-records :factset)
                  config/load-config (fn [_] config)
                  ;; this normally calls System/exit on a cli error; we'd rather have the exception.
                  utils/try+-process-cli! (fn [body] (body))]
      (let [result (apply benchmark/benchmark-main cli-args)]
        (when (benchmark/chan? result)
          (<!! (timeout 1000))
          (close! result)))
      @submitted-records)))

(deftest config-is-required
  (is (thrown+-with-msg?
       [:type ::ks/cli-error]
       #"Missing required argument '--config'"
       (run-benchmark {}))))

(deftest config-is-required
  (is (thrown+-with-msg?
       [:type ::ks/cli-error]
       #"Missing required argument '--numhosts'"
       (run-benchmark {}
                      "--config" "anything.ini"))))

(deftest nummsgs-or-runinterval-is-required
  (is (thrown+-with-msg?
       [:type ::utils/cli-error]
       #"Either -N/--nummsgs or -i/--runinterval is required."
       (run-benchmark {}
                      "--config" "anything.ini"
                      "--numhosts" "42"))))

(deftest runs-with-runinterval
  (let [submitted (run-benchmark {}
                                 "--config" "anything.ini"
                                 "--numhosts" "42"
                                 "--runinterval" "1")]
    (is (>= (count submitted)) (* 3 42))))

(deftest submit-one-record-of-each-type
  (let [submitted (run-benchmark {}
                                 "--config" "anything.ini"
                                 "--numhosts" "1"
                                 "--nummsgs" "1")]
    (is (= 3 (count submitted)))))

(deftest multiple-messages-and-hosts
  (let [submitted (run-benchmark {}
                                 "--config" "anything.ini"
                                 "--numhosts" "2"
                                 "--nummsgs" "3")]
    (is (= 18 (count submitted)))))

(deftest consecutive-reports-are-distinct
  (let [submitted (run-benchmark {}
                                 "--config" "anything.ini"
                                 "--numhosts" "1"
                                 "--nummsgs" "10")
        reports (->> submitted
                     (filter #(= :report (:entity %)))
                     (map :payload))]
    (is (= 10 (->> reports (map :configuration_version) distinct count)))
    (is (= 10 (->> reports (map :start_time) distinct count)))
    (is (= 10 (->> reports (map :end_time) distinct count)))))

(deftest randomize-catalogs-and-factsets
  (let [submitted (run-benchmark {}
                                 "--config" "anything.ini"
                                 "--numhosts" "1"
                                 "--nummsgs" "10"
                                 "--rand-perc" "100")
        catalog-hashes (->> submitted
                            (filter #(= :catalog (:entity %)))
                            (map :payload)
                            (map hash))
        factset-hashes (->> submitted
                            (filter #(= :factset (:entity %)))
                            (map :payload)
                            (map hash))]
    (is (= 10 (count (distinct catalog-hashes))))
    (is (= 10 (count (distinct factset-hashes))))))
