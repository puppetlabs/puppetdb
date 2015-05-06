(ns puppetlabs.puppetdb.extensions.state-overview
  (:require [clj-time.core :refer [hours seconds ago]]
            [puppetlabs.kitchensink.core :refer [parse-number]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]))

(defn fn-produce-body
  [query-fn entity callback]
  (fn [query]
    (query-fn entity :v4 query nil callback)))

(defn state-overview-app
  [query-fn]
  (fn [{:keys [params] :as request}]
   (let [produce-reports (fn-produce-body query-fn :reports (fn [f] (f doall)))
         produce-nodes (fn-produce-body query-fn :nodes (fn [f] (f doall)))
         unresponsive-threshold (let [urt (get params "unresponsive_threshold")]
                                  (if (seq urt)
                                    (-> urt parse-number seconds ago)
                                    (-> 1 hours ago)))
         [{noops :count}] (produce-reports ["extract" [["function" "count"]]
                                            ["and" ["=" "noop" true] ["=" "latest_report?" true]
                                             [">" "end_time" unresponsive-threshold]]])
         basics (produce-reports ["extract" ["status" ["function" "count"]]
                                    ["and" ["=" "noop" false] ["=" "latest_report?" true]
                                     [">" "end_time" unresponsive-threshold]]
                                    ["group_by" "status"]])
         [{unresponsives :count}] (produce-nodes ["extract" [["function" "count"]]
                                                  ["<" "report_timestamp" unresponsive-threshold]])
         [{unreporteds :count}] (produce-nodes ["extract" [["function" "count"]]
                                                ["and" ["null?" "report_timestamp" true]
                                                 ["null?" "report_environment" true]]])]
     (->> (for [{:keys [status count]} basics] [(keyword status) count])
          (into {:unresponsive unresponsives :unreported unreporteds :noop noops})
          (merge {:failed 0 :unchanged 0 :unresponsive 0 :noop 0 :success 0 :unreported 0})
          http/json-response))))
