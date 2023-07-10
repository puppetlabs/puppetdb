(ns puppetlabs.puppetdb.query-timeout-test
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.config :as conf]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.query-eng :refer [diagnostic-inter-row-sleep]]
   [puppetlabs.puppetdb.query.monitor :as qmon]
   [puppetlabs.puppetdb.random :refer [random-string]]
   [puppetlabs.puppetdb.scf.storage :refer [add-certnames]]
   [puppetlabs.puppetdb.testutils.db :refer [*db* *read-db* with-unconnected-test-db]]
   [puppetlabs.puppetdb.testutils.services
    :refer [*base-url*
            create-temp-config
            call-with-puppetdb-instance
            with-puppetdb]]
   [puppetlabs.puppetdb.time :as time]
   [puppetlabs.puppetdb.utils :refer [base-url->str-with-prefix]]))

(defn timed-nodes-query
  "Returns [response elapsed-sec]"
  [& {:keys [timeout origin]}]
  (let [start (time/ephemeral-now-ns)
        {:keys [body] :as res}
        (-> (assoc *base-url* :prefix "/pdb/query/v4/nodes")
            base-url->str-with-prefix
            (http/get (cond-> {:throw-exceptions false
                               :content-type :json
                               :character-encoding "UTF-8"
                               :accept :json
                               :as :stream}
                        origin (assoc-in [:query-params "origin"] origin)
                        timeout (assoc-in [:query-params "timeout"] (str timeout)))))
        body (slurp body)
        end (time/ephemeral-now-ns)]
    [(assoc res :body body)
     (/ (- end start) 1e9)]))

;; From some quick testing, the current output buffering appears to be
;; about 60k, i.e. curl won't see any data until about that much json
;; has been generated.

(def certnames (repeatedly 100 #(random-string 2000)))

(defn test-behavior-with-no-timeouts []
  (let [[{:keys [status body]}] (timed-nodes-query)]
    (is (= 200 status))
    (is (= 100 (count (json/parse-string body)))))
  (let [[{:keys [status body]}] (timed-nodes-query :timeout 5)]
    (is (= 200 status))
    (is (= 100 (count (json/parse-string body)))))
  (let [[{:keys [status body]} elapsed] (timed-nodes-query :timeout 0.1)]
    (is (= 200 status) "timeout after first row should produce 200")
    (is (str/includes? body "exceeded timeout"))
    (is (< 0 elapsed 0.2))))

(defn suppress-query-monitor [config]
  (assoc-in config [:puppetdb ::conf/test ::qmon/monitor-queries?] false))

(deftest http-parameter-timeouts
  (with-puppetdb {:to-config suppress-query-monitor}
    (jdbc/with-db-transaction [] (add-certnames certnames))
    (with-redefs [diagnostic-inter-row-sleep 0.01]
      (testing "no timeout behavior with config defaults"
        (test-behavior-with-no-timeouts)))))

(defn config [& {:keys [default max]}]
  (cond-> (assoc (create-temp-config) :database *db* :read-database *read-db*)
    true suppress-query-monitor
    default (assoc-in [:puppetdb :query-timeout-default] default)
    max (assoc-in [:puppetdb :query-timeout-max] max)))

(deftest zero-max-and-default-behaves-same-as-none
  (with-unconnected-test-db
    (call-with-puppetdb-instance (config :default "0" :max "0")
     (fn []
       (jdbc/with-db-transaction [] (add-certnames certnames))
       (with-redefs [diagnostic-inter-row-sleep 0.01]
         (testing "no timeout behavior with config zeroes"
           (test-behavior-with-no-timeouts)))))))

(deftest default-timeout-behavior
  (with-unconnected-test-db
    (call-with-puppetdb-instance (config :default "0.3")
     (fn []
       (jdbc/with-db-transaction [] (add-certnames certnames))
       (with-redefs [diagnostic-inter-row-sleep 0.01]
         (let [[{:keys [status body]} elapsed] (timed-nodes-query)]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout"))
           (is (< 0.2 elapsed 0.4)))
         (let [[{:keys [status body]} elapsed] (timed-nodes-query :timeout 0.1)]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout"))
           (is (< 0 elapsed 0.2)))
         (let [[{:keys [status body]} elapsed] (timed-nodes-query :timeout 0.4)]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout"))
           (is (< 0.3 elapsed 0.5))))))))

(deftest max-timeout-behavior
  (with-unconnected-test-db
    (call-with-puppetdb-instance (config :max "0.3")
     (fn []
       (jdbc/with-db-transaction [] (add-certnames certnames))
       (with-redefs [diagnostic-inter-row-sleep 0.01]
         (let [[{:keys [status body]} elapsed] (timed-nodes-query)]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout"))
           (is (< 0.2 elapsed 0.4)))
         (let [[{:keys [status body]} elapsed] (timed-nodes-query :timeout 0.1)]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout"))
           (is (< 0 elapsed 0.2)))
         (let [[{:keys [status body]} elapsed] (timed-nodes-query :timeout 0.5)]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout"))
           (is (< 0.2 elapsed 0.4))))))))

(deftest max-overrides-default
  (with-unconnected-test-db
    (call-with-puppetdb-instance (config :max "0.2" :default "0.5")
     (fn []
       (jdbc/with-db-transaction [] (add-certnames certnames))
       (with-redefs [diagnostic-inter-row-sleep 0.01]
         (let [[{:keys [status body]} elapsed] (timed-nodes-query)]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout"))
           (is (< 0.1 elapsed 0.3))))))))

(deftest sync-overrides-max
  (with-unconnected-test-db
    (call-with-puppetdb-instance
     (config :max "0.1")
     (fn []
       (jdbc/with-db-transaction [] (add-certnames certnames))
       (with-redefs [diagnostic-inter-row-sleep 0.01]
         (let [[{:keys [status body]}]
               (timed-nodes-query :timeout "2")]
           (is (= 200 status) "timeout after first row should produce 200")
           (is (str/includes? body "exceeded timeout")))
         (doseq [origin ["puppet:puppetdb-sync-batch" "puppet:puppetdb-sync-summary"]]
           (let [[{:keys [status body]}]
                 (timed-nodes-query :timeout "4" :origin origin)]
             (is (= 200 status))
             (is (not (str/includes? body "exceeded timeout")) "unexpected timeout")
             (is (= 100 (count (json/parse-string body)))))))))))
