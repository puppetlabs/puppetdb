(ns puppetlabs.puppetdb.acceptance.node-ttl
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.testutils.http :as tuhttp]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.test-protocols :refer [called?]]
            [puppetlabs.puppetdb.time :refer [now]]))

(deftest test-node-ttl
  (tu/with-coordinated-fn run-purge-nodes puppetlabs.puppetdb.cli.services/purge-nodes!
    (tu/with-coordinated-fn run-expire-nodes puppetlabs.puppetdb.cli.services/auto-expire-nodes!
      (with-test-db
        (svc-utils/call-with-puppetdb-instance
         (-> (svc-utils/create-temp-config)
             (assoc :database *db*)
             (assoc-in [:database :node-ttl] "1s")
             (assoc-in [:database :node-purge-ttl] "1s"))
         (fn []
           (let [certname "foo.com"
                 catalog (-> (get-in wire-catalogs [8 :empty])
                             (assoc :certname certname
                                    :producer_timestamp (now)))]
             (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) certname
                                          "replace catalog" 8 catalog)

             (is (= 1 (count (:body (svc-utils/get (svc-utils/query-url-str "/nodes"))))))
             (is (nil? (-> (svc-utils/query-url-str "/nodes/foo.com")
                           svc-utils/get
                           (get-in [:body :expired]))))
             (Thread/sleep 1000)
             (run-expire-nodes)

             (is (= 0 (count (:body (svc-utils/get (svc-utils/query-url-str "/nodes"))))))
             (is (->  (svc-utils/query-url-str "/nodes/foo.com")
                      svc-utils/get
                      (get-in [:body :expired])))
             (Thread/sleep 1000)
             (run-purge-nodes)

             (is (= 0
                    (-> (svc-utils/query-url-str "/nodes")
                        svc-utils/get
                        :body
                        count)))
             (is (= {:error "No information is known about node foo.com"}
                    (-> (svc-utils/query-url-str "/nodes/foo.com")
                        svc-utils/get
                        :body))))))))))

(deftest test-zero-gc-interval
  (with-redefs [puppetlabs.puppetdb.cli.services/purge-nodes! (tu/mock-fn)]
    (with-test-db
      (svc-utils/call-with-puppetdb-instance
       (-> (svc-utils/create-temp-config)
           (assoc :database *db*)
           (assoc-in [:database :node-ttl] "0s")
           (assoc-in [:database :report-ttl] "0s")
           (assoc-in [:database :node-purge-ttl] "1s")
           (assoc-in [:database :gc-interval] 0))
       (fn []
         (Thread/sleep 1500)
         (is (not (called? puppetlabs.puppetdb.cli.services/purge-nodes!))))))))
