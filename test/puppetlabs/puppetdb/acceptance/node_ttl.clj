(ns puppetlabs.puppetdb.acceptance.node-ttl
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.cli.services :as cli-svc]
   [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
   [puppetlabs.puppetdb.test-protocols :refer [called?]]
   [puppetlabs.puppetdb.testutils :as tu]
   [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
   [puppetlabs.puppetdb.testutils.http :as tuhttp]
   [puppetlabs.puppetdb.testutils.services :as svc-utils
    :refer [*server* with-pdb-with-no-gc]]
   [puppetlabs.puppetdb.time :as tc :refer [now parse-wire-datetime]]
   [puppetlabs.puppetdb.utils :as utils]
   [puppetlabs.trapperkeeper.app :refer [get-service]]))

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

(deftest configure-expiration-behavior
  (let [lifetime-ms 1
        lifetime-cfg (str lifetime-ms "ms")]
    (with-test-db
      (svc-utils/call-with-single-quiet-pdb-instance
       (-> (svc-utils/create-temp-config)
           (assoc :database *db*)
           (assoc-in [:database :node-ttl] lifetime-cfg)
           (assoc-in [:database :node-purge-ttl] lifetime-cfg))
       (fn []
         (let [pdb (get-service *server* :PuppetDBServer)
               do-cmd (fn [wire cmd version]
                        (svc-utils/sync-command-post (svc-utils/pdb-cmd-url)
                                                     (:certname wire)
                                                     cmd
                                                     version
                                                     wire))
               add-catalog (fn [certname stamp]
                             (-> (get-in wire-catalogs [8 :empty])
                                 (assoc :certname certname
                                        :producer_timestamp stamp)
                                 (do-cmd "replace catalog" 8)))
               add-facts (fn [certname stamp facts]
                           (do-cmd {:producer_timestamp (str stamp)
                                    :timestamp stamp
                                    :producer nil
                                    :certname certname
                                    :environment "dev"
                                    :values facts}
                                   "replace facts" 5))
               deactivate (fn [certname stamp]
                            (do-cmd {:producer_timestamp stamp
                                     :certname certname}
                                    "deactivate node" 3))
               set-expire (fn [certname stamp expire-facts?]
                            (do-cmd {:producer_timestamp stamp
                                     :certname certname
                                     :expire {:facts expire-facts?}}
                                    "configure expiration" 1))
               compare-certs #(compare (get %1 "certname") (get %2 "certname"))
               nodes (fn []
                       (->> {:query ["from" "nodes"
                                     ["extract" ["certname" "expired"]
                                      ["or"
                                       ["=" "node_state" "active"]
                                       ["=" "node_state" "inactive"]]]]}
                            (svc-utils/post (svc-utils/query-url-str ""))
                            :body
                            slurp
                            json/parse-string
                            (sort compare-certs)))
               facts (fn []
                       (->> {:query ["from" "factsets"
                                     ["extract" ["certname" "facts"]
                                      ["or"
                                       ["=" "node_state" "active"]
                                       ["=" "node_state" "inactive"]]]]}
                            (svc-utils/post (svc-utils/query-url-str ""))
                            :body
                            slurp
                            json/parse-string
                            (map #(update % "facts" (fn [v] (get v "data"))))
                            (sort compare-certs)))]

           (testing "facts don't expire/purge when expire configured to false"
             (let [start-time (now)]
               (set-expire "foo" (now) false)
               (add-catalog "foo" (now))
               (add-facts "foo" (now) {:x 1})
               (add-facts "bar" (now) {:y 1})
               (Thread/sleep (inc lifetime-ms))
               (is (= [{"certname" "bar" "expired" nil}
                       {"certname" "foo" "expired" nil}]
                      (nodes)))
               (cli-svc/clean pdb ["expire_nodes"])
               (let [result (nodes)]
                 (is (= 2 (count result)))
                 (is (= {"certname" "foo" "expired" nil} (second result)))
                 (is (= "bar" (-> result first (get "certname"))))
                 (is (tc/after? (now)
                                (-> result first (get "expired") parse-wire-datetime))))
               (= [{"certname" "bar", "facts" [{"name" "y", "value" 1}]}
                   {"certname" "foo", "facts" [{"name" "x", "value" 1}]}]
                  (facts))
               (cli-svc/clean pdb ["purge_nodes"])
               (is (= [{"certname" "foo" "expired" nil}] (nodes)))
               (= [{"certname" "foo", "facts" [{"name" "x", "value" 1}]}]
                  (facts))))

           (testing "changing expiration from false to true allows expire/purge"
             (let [start-time (now)]
               (set-expire "foo" (now) true)
               (cli-svc/clean pdb ["expire_nodes"])
               (let [result (nodes)]
                 (is (= 1 (count result)))
                 (is (= "foo" (-> result first (get "certname"))))
                 (is (tc/after? (now)
                                (-> result first (get "expired") parse-wire-datetime))))
               (= [{"certname" "foo", "facts" [{"name" "x", "value" 1}]}]
                  (facts))
               (cli-svc/clean pdb ["purge_nodes"])
               (is (= [] (nodes)))
               (= [] (facts))))

           (testing "nodes with unexpirable facts deactivate properly"
             (set-expire "foo" (now) false)
             (add-catalog "foo" (now))
             (add-facts "foo" (now) {:x 1})
             (is (= [{"certname" "foo" "expired" nil}] (nodes)))
             (cli-svc/clean pdb [])
             (is (= [{"certname" "foo" "expired" nil}] (nodes)))
             (deactivate "foo" (now))
             (is (= [{"certname" "foo" "expired" nil}] (nodes)))
             (cli-svc/clean pdb [])
             (is (= [] (nodes))))))))))

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
