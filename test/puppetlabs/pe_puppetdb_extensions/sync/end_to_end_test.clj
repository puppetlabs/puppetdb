(ns puppetlabs.pe-puppetdb-extensions.sync.end-to-end-test
  (:refer-clojure :exclude [sync])
  (:require [clojure.test :refer :all :exclude [report]]
            [clj-time.core :as t]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pe-puppetdb-extensions.sync.core :as sync-core]
            [puppetlabs.pe-puppetdb-extensions.sync.sync-test-utils :refer :all]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post sync-config
                     with-ext-instances with-related-ext-instances]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage-test :refer [expire-node!]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.testutils.log :refer [with-log-level with-logging-to-atom]]
            [puppetlabs.puppetdb.testutils
             :refer [is-equal-after block-until-results with-alt-mq]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.cheshire :as json]
            [slingshot.slingshot :refer [throw+]]
            [slingshot.test]))

(deftest refuses-to-sync-to-unconfigured-remote
  (let [cfg #(ks/dissoc-in (sync-config nil) [:sync :allow-unsafe-sync-triggers])]
    (with-ext-instances [pdb1 (cfg) pdb2 (cfg)]
      (with-alt-mq (:mq-name pdb1)
        (submit-report pdb1 report))
      (with-alt-mq (:mq-name pdb2)
        (is (.contains
             (:body (start-sync :from pdb1 :to pdb2))
             "Refusing to sync. PuppetDB is not configured to sync with"))))))

(deftest end-to-end-report-replication
  (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
    (with-alt-mq (:mq-name pdb1)
      (submit-report pdb1 report))
    (with-alt-mq (:mq-name pdb2)
      (sync :from pdb1 :to pdb2))
    (is-equal-after
     #(-> %
          first
          (dissoc :receive_time :producer_timestamp
                  :resources :resource_events))
     (svcs/get-reports (:query-url pdb1) (:certname report))
     (svcs/get-reports (:query-url pdb2) (:certname report)))))

(deftest end-to-end-factset-replication
  (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
    (with-alt-mq (:mq-name pdb1)
      (submit-factset pdb1 facts))
    (with-alt-mq (:mq-name pdb2)
      (sync :from pdb1 :to pdb2))
    (is-equal-after
     #(-> %
          first
          (update-in [:facts :data] set)
          without-timestamp)
     (svcs/get-factsets (:query-url pdb1) (:certname facts))
     (svcs/get-factsets (:query-url pdb2) (:certname facts)))))

(defn get-historical-catalogs [base-url certname]
  (svcs/get-json
   (assoc base-url :prefix utils/pe-pdb-url-prefix :version :v1)
   "/historical-catalogs"
   {:query-params {:query (json/generate-string [:= :certname certname])}}))

(deftest end-to-end-catalog-replication
  (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
    (with-alt-mq (:mq-name pdb1)
      (submit-catalog pdb1 (assoc catalog
                                  :producer_timestamp (t/now)
                                  :transaction_uuid "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa"))
      (submit-catalog pdb1 (assoc catalog
                                  :producer_timestamp (-> 1 t/hours t/ago)
                                  :transaction_uuid  "bbbbbbbb-1111-aaaa-1111-aaaaaaaaaaaa")))
    (with-alt-mq (:mq-name pdb2)
      (sync :from pdb1 :to pdb2))

    ;; FIXME: This is a workaround for the queue retry issue and should be
    ;; removed once that is resolved.
    (Thread/sleep 15000)

    (let [pdb1-catalogs (get-historical-catalogs (:query-url pdb1) (:certname catalog))
          pdb2-catalogs (get-historical-catalogs (:query-url pdb2) (:certname catalog))]
      (is (= (count pdb1-catalogs) (count pdb2-catalogs)))
      (is (= (sort-by :transaction_uuid pdb1-catalogs)
             (sort-by :transaction_uuid pdb2-catalogs))))))

(deftest deactivate-then-sync
  (let [certname (:certname catalog)]
    (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
      ;; sync a node
      (with-alt-mq (:mq-name pdb1)
        (submit-catalog pdb1 catalog)
        (submit-factset pdb1 facts)
        (submit-report pdb1 report)
        (deactivate-node pdb1 certname))
      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2)
        (let [node (get-node (:query-url pdb2) certname)]
          (is (:deactivated node)))))))

(deftest sync-after-deactivate
  (let [certname (:certname catalog)]
    (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
      ;; sync a node
      (with-alt-mq (:mq-name pdb1)
        (submit-catalog pdb1 catalog)
        (submit-factset pdb1 facts)
        (submit-report pdb1 report))
      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2)
        (let [node (get-node (:query-url pdb2) certname)]
          (is (nil? (:deactivated node)))))

      ;; then deactivate and sync
      (with-alt-mq (:mq-name pdb1)
        (deactivate-node pdb1 certname))
      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2)
        (let [node (get-node (:query-url pdb2) certname)]
          (is (:deactivated node)))))))

(deftest periodic-sync
  (let [sync-interval "2s"]
    (let [certname (:certname facts)
          facts-from #(-> % :query-url (svcs/get-factsets certname)
                          first)]
      (with-related-ext-instances [master (sync-config nil)
                                   mirror (sync-config nil)]
        (fn [cfg running-instances]
          (if-not (= 1 (count running-instances))
            cfg
            (let [url (base-url->str (:server-url (running-instances 0)))]
              (assoc cfg :sync {:remotes [{:server_url url
                                           :interval sync-interval}]
                                :allow-unsafe-cleartext-sync true}))))
        (with-alt-mq (:mq-name master)
          (is (nil? (facts-from mirror)))
          (blocking-command-post (:command-url master) certname
                                 "replace facts" 5 facts)
          @(block-until-results 100 (facts-from master)))
        @(block-until-results 100 (facts-from mirror))
        (is-equal-after
         #(-> %
              (update-in [:facts :data] set)
              without-timestamp)
         (facts-from mirror)
         (facts-from master))))))

(deftest pull-record-that-wouldnt-be-expired-locally
  (let [certname (:certname catalog)]
    (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
      ;; add a node to pdb1
      (with-alt-mq (:mq-name pdb1)
        (submit-catalog pdb1 catalog))
      ;; expire it manually
      (jdbc/with-transacted-connection (:db pdb1)
        (expire-node! certname (t/now)))
      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2)
        ;; the node shouldn't be expired from pdb2's perspective, so it
        ;; should be pulled.
        (let [node (get-node (:query-url pdb2) certname)]
          (is (not (:expired node))))))))

(deftest dont-pull-record-that-would-be-expired-locally
  (let [certname (:certname catalog)]
    (with-ext-instances [pdb1 (sync-config nil)
                         pdb2 (assoc-in (sync-config nil)
                                        [:database :node-ttl] "1d")]
      ;; add a node to pdb1 in the distant past
      (with-alt-mq (:mq-name pdb1)
        (submit-catalog pdb1 (assoc catalog :producer_timestamp (-> 3 t/weeks t/ago))))
      (with-alt-mq (:mq-name pdb2)
        (trigger-sync (base-url->str (:query-url pdb1))
                      (str (base-url->str (:sync-url pdb2)) "/trigger-sync"))
        ;; the other tests poll until a record exists to make sure sync worked, but that
        ;; doesn't make sense here. Just wait a little while instead.
        (Thread/sleep 5000)
        ;; the node should be expired from pdb2's perspective. So it
        ;; shouldn't get pulled
        (is (thrown+? [:status 404]
                      (get-node (:query-url pdb2) certname)))))))

(deftest three-node-end-to-end
  (with-ext-instances [pdb1 (sync-config nil)
                       pdb2 (sync-config nil)
                       pdb3 (sync-config nil)]
    (with-alt-mq (:mq-name pdb1)
      (submit-factset pdb1 facts))
    (with-alt-mq (:mq-name pdb2)
      (sync :from pdb1 :to pdb2))
    (with-alt-mq (:mq-name pdb3)
      (sync :from pdb2 :to pdb3))
    (is-equal-after
     #(->> %
           (map (fn [row] (update-in row [:facts :data] set)))
           (map without-timestamp)
           (into #{}))
     (svcs/get-factsets (:query-url pdb1) (:certname facts))
     (svcs/get-factsets (:query-url pdb2) (:certname facts))
     (svcs/get-factsets (:query-url pdb3) (:certname facts)))))

(defn- event->map [event]
  {:level (str (.getLevel event))
   :message (.getMessage event)
   :map (.semlogMap (.getMarker event))})

(defn- ordered-matches? [predicates items]
  (loop [predicates predicates
         items items]
    (if-not (seq predicates)
      true
      (let [[predicate & predicates] predicates
            match (drop-while (complement predicate) items)]
        (when (seq match)
          (recur predicates (next match)))))))

(defn- elapsed-correct? [m]
  (if (#{"finished" "error"} (:event m))
    (is (number? (:elapsed m)))
    (is (nil? (:elapsed m)))))

(defn- ok-correct? [m]
  (case (:event m)
    ("finished") (is (:ok m))
    ("error") (is (not (:ok m)))
    true))

(defmacro verify-sync [event item]
  `(let [item# ~item
         m# (:map item#)]
     (when (= "sync" (:phase m#))
       (and
        (is (= ~event (:event m#)))
        (is (= "INFO" (:level item#)))
        (is (string? (:remote m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(defmacro verify-entity-sync [event name xfer fail item]
  `(let [item# ~item
         m# (:map item#)]
     (assert (or (zero? ~fail) (not= "start" (:event m#))))
     (when (and (= "entity" (:phase m#))
                (= ~name (:entity m#)))
       (and
        (is (= "INFO" (:level item#)))
        (is (= ~event (:event m#)))
        (is (string? (:remote m#)))
        (is (= ~xfer (:transferred m#)))
        (is (= ~fail (:failed m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(defmacro verify-record-sync [event name item]
  `(let [item# ~item
         m# (:map item#)]
     (when (and (= "record" (:phase m#))
                (= ~name (:entity m#)))
       (and
        (is (= "DEBUG" (:level item#)))
        (is (= ~event (:event m#)))
        (is (string? (:remote m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(defmacro verify-deactivate-sync [event item]
  `(let [item# ~item
         m# (:map item#)]
     (when (= "deactivate" (:phase m#))
       (and
        (is (= "DEBUG" (:level item#)))
        (is (= ~event (:event m#)))
        (is (string? (:certname m#)))
        (is (instance? java.util.Date (:producer_timestamp m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(deftest sync-logging
  (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
    (let [events (let [log (atom [])]
                   (with-log-level "sync" :debug
                     (with-logging-to-atom "sync" log
                       (let [certname (:certname catalog)]
                         ;; Submit a normal sequence of commands
                         (with-alt-mq (:mq-name pdb1)
                           (submit-catalog pdb1 catalog)
                           (submit-factset pdb1 facts)
                           (submit-report pdb1 report)
                           (deactivate-node pdb1 certname))
                         (with-alt-mq (:mq-name pdb2)
                           (sync :from pdb1 :to pdb2
                                 :check-with get-node :check-for certname)))))
                   (map event->map @log))]
      ;; Verify expected partial orderings
      (is (ordered-matches?
           [#(verify-sync "start" %)
            #(verify-entity-sync "start" "factsets" 0 0 %)
            #(verify-record-sync "start" "factsets" %)
            #(verify-record-sync "finished" "factsets" %)
            #(verify-entity-sync "finished" "factsets" 1 0 %)
            #(verify-sync "finished" %)]
           events))
      (is (ordered-matches?
           [#(verify-sync "start" %)
            #(verify-entity-sync "start" "historical_catalogs" 0 0 %)
            #(verify-record-sync "start" "historical_catalogs" %)
            #(verify-record-sync "finished" "historical_catalogs" %)
            #(verify-entity-sync "finished" "historical_catalogs" 1 0 %)
            #(verify-sync "finished" %)]
           events))
      (is (ordered-matches?
           [#(verify-sync "start" %)
            #(verify-entity-sync "start" "reports" 0 0 %)
            #(verify-record-sync "start" "reports" %)
            #(verify-record-sync "finished" "reports" %)
            #(verify-entity-sync "finished" "reports" 1 0 %)
            #(verify-sync "finished" %)]
           events))
      (is (ordered-matches?
           [#(verify-sync "start" %)
            #(verify-entity-sync "start" "nodes" 0 0 %)
            #(verify-deactivate-sync "start" %)
            #(verify-deactivate-sync "finished" %)
            #(verify-entity-sync "finished" "nodes" 1 0 %)
            #(verify-sync "finished" %)]
           events)))))

(deftest sync-logging-entity-failure
  (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
    (let [events
          ;; Force a transfer error while syncing a factset.
          (let [log (atom [])]
            (with-log-level "sync" :debug
              (with-logging-to-atom "sync" log
                (let [certname (:certname catalog)]
                  (with-alt-mq (:mq-name pdb1)
                    (submit-factset pdb1 facts))
                  (with-alt-mq (:mq-name pdb2)
                    (with-redefs [sync-core/transfer-batch
                                  (fn [& args]
                                    {:transferred 0
                                     :failed 1})]
                      (perform-sync (base-url->str (:query-url pdb1))
                                    (str (base-url->str (:sync-url pdb2))
                                         "/trigger-sync")))))))
            (map event->map @log))]
      (is (ordered-matches?
           [#(verify-sync "start" %)
            #(verify-entity-sync "start" "factsets" 0 0 %)
            ;; Check that factsets :failed is 1.
            #(verify-entity-sync "finished" "factsets" 0 1 %)
            #(verify-sync "finished" %)]
           events)))))
