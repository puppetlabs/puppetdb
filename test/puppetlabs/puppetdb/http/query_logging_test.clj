(ns puppetlabs.puppetdb.http.query-logging-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.puppetdb.cli.services :as svcs]
   [puppetlabs.puppetdb.testutils.db :refer [with-test-db *db*]]
   [puppetlabs.puppetdb.testutils.http
    :refer [call-with-http-app query-response with-http-app*]]
   [puppetlabs.puppetdb.testutils.services
    :refer [call-with-puppetdb-instance create-temp-config *server*]]
   [puppetlabs.puppetdb.utils :refer [println-err]]
   [puppetlabs.trapperkeeper.app :refer [get-service]]
   [puppetlabs.trapperkeeper.testutils.logging :as tk-log
    :refer [with-logged-event-maps]])
  (:import
   (java.util UUID)))

(defn logs-include?
  "Returns true if only one instance of unique-msg is found in the log."
  [logs unique-msg]
  (= 1 (->> logs (filter #(str/includes? % unique-msg)) count)))

(defn count-logs-uuids
  "Returns a map with log uuids as keys and the number of times
  that uuid appears in the log as its value. i.e. {<uuid> 2}."
  [logs]
  (->> logs
       ;; query logs in 'PDBQuery:UUID:AST/SQL' format
       (map #(str/split % #":"))
       (map second)
       (group-by identity)
       (kitchensink/mapvals count)))

(defn keep-only-pdbquery-logs
  "Filters out any log messages which don't have the PDBQuery prefix."
  [logs]
  ;; the drop-joins tests log other messages at the debug level
  (filter #(str/includes? % "PDBQuery") logs))

(defn prep-logs [logs]
  (->> @logs (map :message) keep-only-pdbquery-logs))

(deftest queries-are-logged-when-log-queries-is-true
  (tk-log/with-log-level "puppetlabs.puppetdb.query-eng" :debug
    (with-test-db
      (with-http-app* #(assoc % :log-queries true)
        (doseq [[query ast-exp sql-exp]
                [[["/v4" ["from" "nodes"]]
                  "\"from\" \"nodes\""
                  "latest_report_noop_pending"]
                 [["/v4" ["from" "facts"]]
                  "\"from\" \"facts\""
                  "(jsonb_each((stable||volatile)))"]]]
          (with-logged-event-maps events
            (is (= 200 (:status (apply query-response :get query))))

            (let [events @events
                  uuid (some->> events
                                (some #(->> % :message (re-find #"^PDBQuery:([^:]+):")))
                                second)
                  qev-matching (fn [expected]
                                 (fn [{:keys [message] :as event}]
                                   (and (str/starts-with? message (str "PDBQuery:" uuid ":"))
                                        (str/includes? message expected))))]

              (is (uuid? (UUID/fromString uuid)))

              (let [asts (filter (qev-matching ast-exp) events)]
                (when-not (is (= 1 (count asts)))
                  (println-err "Unexpected log:" events)))

              (let [sqls (filter (qev-matching sql-exp) events)]
                (when-not (is (= 1 (count sqls)))
                  (println-err "Unexpected log:" events))))))))))

(deftest no-queries-are-logged-when-log-queires-is-false
  (tk-log/with-logged-event-maps logs
    (tk-log/with-log-level "puppetlabs.puppetdb.query-eng" :debug
      (with-test-db
        (call-with-http-app
         (fn []
           (is (= 200 (:status (query-response :get "/v4" ["from" "nodes"]))))
           (is (= 200 (:status (query-response :get "/v4" ["from" "facts"]))))
           (is (empty? (prep-logs logs))))
         #(assoc % :log-queries false))))))

(deftest queries-generated-by-PuppetDBServer-tk-service-query-are-logged
  (tk-log/with-logged-event-maps logs
    (tk-log/with-log-level "puppetlabs.puppetdb.query-eng" :debug
      (with-test-db
        (call-with-puppetdb-instance
         (-> (create-temp-config)
             (update :puppetdb (fn [x] (merge x {:log-queries "true"})))
             (assoc :database *db*))
         (fn []
           (let [pdb-service (get-service *server* :PuppetDBServer)]
             ;; submit a few queires to the PuppetDBServer TK service query method
             (svcs/query pdb-service :v4 ["from" "facts"] nil identity)
             (svcs/query pdb-service :v4 ["from" "nodes"] nil identity)

             (let [logs (prep-logs logs)]
               (testing "uuids match for the AST and SQL logged per query"
                 (is (= [2 2] (vals (count-logs-uuids logs)))))

               (testing "AST/SQL is logged for both queries above"
                 ;; match the AST/SQL logs for nodes query
                 (is (logs-include? logs "\"from\" \"nodes\""))
                 (is (logs-include? logs "latest_report_noop_pending"))

                 ;; match the AST/SQL logs for facts query
                 (is (logs-include? logs "\"from\" \"facts\""))
                 (is (logs-include? logs "(jsonb_each((stable||volatile)))")))))))))))

(deftest no-PuppetDBServer-tk-service-queries-are-logged-when-log-queries-is-false
  (tk-log/with-logged-event-maps logs
    (tk-log/with-log-level "puppetlabs.puppetdb.query-eng" :debug
      (with-test-db
        (call-with-puppetdb-instance
         (-> (create-temp-config)
             (update :puppetdb (fn [x] (merge x {:log-queries "false"})))
             (assoc :database *db*))
         (fn []
           (let [pdb-service (get-service *server* :PuppetDBServer)]
             ;; submit a few queires to the PuppetDBServer TK service query method
             (svcs/query pdb-service :v4 ["from" "facts"] nil identity)
             (svcs/query pdb-service :v4 ["from" "nodes"] nil identity)
             (is (empty? (prep-logs logs))))))))))
