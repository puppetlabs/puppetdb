(ns puppetlabs.pe-puppetdb-extensions.state-overview
  (:require [clj-time.core :refer [hours seconds ago]]
            [puppetlabs.kitchensink.core :refer [parse-number]]
            [puppetlabs.puppetdb.http :as http]))

(defn fn-produce-body
  [query-fn]
  (fn [query]
    (query-fn :v4 query nil doall)))

(defn state-overview-app
  [query-fn]
  (fn [{:keys [params] :as request}]
    (let [produce-body (fn-produce-body query-fn)
          unresponsive-threshold (let [urt (get params "unresponsive_threshold")]
                                   (if (seq urt)
                                     (-> urt parse-number seconds ago)
                                     (-> 1 hours ago)))
          [{noops :count}] (produce-body ["from" "reports"
                                          ["extract" [["function" "count"]]
                                           ["and"
                                            ["=" ["node" "active"] true]
                                            ["=" "noop" true]
                                            ["=" "latest_report?" true]
                                            [">" "end_time" unresponsive-threshold]]]])
          basics (produce-body ["from" "reports"
                                ["extract" ["status" ["function" "count"]]
                                 ["and"
                                  ["=" ["node" "active"] true]
                                  ["=" "noop" false]
                                  ["=" "latest_report?" true]
                                  [">" "end_time" unresponsive-threshold]]
                                 ["group_by" "status"]]])
          [{unresponsives :count}] (produce-body ["from" "nodes"
                                                  ["extract" [["function" "count"]]
                                                   ["and"
                                                    ["=" ["node" "active"] true]
                                                    ["<" "report_timestamp" unresponsive-threshold]]]])
          [{unreporteds :count}] (produce-body ["from" "nodes"
                                                ["extract" [["function" "count"]]
                                                 ["and"
                                                  ["=" ["node" "active"] true]
                                                  ["null?" "report_timestamp" true]
                                                  ["null?" "report_environment" true]]]])]
      (->> (for [{:keys [status count]} basics] [(keyword status) count])
           (into {:unresponsive unresponsives :unreported unreporteds :noop noops})
           (merge {:failed 0 :unchanged 0 :unresponsive 0 :noop 0 :changed 0 :unreported 0})
           http/json-response))))
