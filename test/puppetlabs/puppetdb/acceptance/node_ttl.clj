(ns puppetlabs.puppetdb.acceptance.node-ttl
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils :refer [*base-url*]]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.testutils.http :as tuhttp]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.testutils :as tu]))

(deftest test-node-ttl
  (tu/with-coordinated-fn run-purge-nodes puppetlabs.puppetdb.cli.services/purge-nodes!
    (tu/with-coordinated-fn run-expire-nodes puppetlabs.puppetdb.cli.services/auto-expire-nodes!
      (svc-utils/call-with-puppetdb-instance
       (-> (svc-utils/create-config)
           (assoc-in [:database :node-ttl] "1s")
           (assoc-in [:database :node-purge-ttl] "1s"))
       (fn []
         (let [certname "foo.com"
               catalog (-> (get-in wire-catalogs [6 :empty])
                           (assoc :certname certname
                                  :producer_timestamp (now)))]
           (svc-utils/sync-command-post
            (tu/command-base-url *base-url*)
            "replace catalog" 6 catalog)

           (is (= 1 (count (:body (tuhttp/pdb-get
                                   (utils/pdb-query-base-url (:host *base-url*) (:port *base-url*))
                                   "/nodes")))))
           (is (nil? (:expired (:body (tuhttp/pdb-get
                                       (utils/pdb-query-base-url (:host *base-url*) (:port *base-url*))
                                       "/nodes/foo.com")))))
           (Thread/sleep 1000)
           (run-expire-nodes)

           (is (= 0 (count (:body (tuhttp/pdb-get
                                   (utils/pdb-query-base-url (:host *base-url*) (:port *base-url*))
                                   "/nodes")))))
           (is (:expired (:body (tuhttp/pdb-get
                                 (utils/pdb-query-base-url (:host *base-url*) (:port *base-url*))
                                 "/nodes/foo.com"))))
           (Thread/sleep 1000)
           (run-purge-nodes)

           (is (= 0 (count (:body (tuhttp/pdb-get
                                   (utils/pdb-query-base-url (:host *base-url*) (:port *base-url*))
                                   "/nodes")))))
           (is (= {:error "No information is known about node foo.com"}
                  (:body (tuhttp/pdb-get
                          (utils/pdb-query-base-url (:host *base-url*) (:port *base-url*))
                          "/nodes/foo.com"))))))))))
