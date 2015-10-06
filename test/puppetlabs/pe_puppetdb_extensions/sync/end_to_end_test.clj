(ns puppetlabs.pe-puppetdb-extensions.sync.end-to-end-test
  (:refer-clojure :exclude [sync])
  (:require [clojure.test :refer :all :exclude [report]]
            [clj-time.core :as t]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pe-puppetdb-extensions.sync.core :as sync-core]
            [puppetlabs.pe-puppetdb-extensions.sync.sync-test-utils :refer :all]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils :refer [blocking-command-post]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.testutils.log :refer [with-log-level with-logging-to-atom]]
            [puppetlabs.puppetdb.testutils :refer [=-after? block-until-results]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [slingshot.slingshot :refer [throw+]]
            [slingshot.test]))

(deftest refuses-to-sync-to-unconfigured-remote
  (with-pdbs (comp #(ks/dissoc-in % [:sync :allow-unsafe-sync-triggers])
                  (default-pdb-configs 2))
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-report pdb1 report))

      (with-alt-mq (:mq-name pdb2)
        (is (.contains
             (:body (start-sync :from pdb1 :to pdb2))
             "Refusing to sync. PuppetDB is not configured to sync with"))))))

(deftest end-to-end-report-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-report pdb1 report))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2))

      (is (=-after? #(dissoc % :receive_time :producer_timestamp :resources :resource_events)
                    (first (svcs/get-reports (:query-url pdb1) (:certname report)))
                    (first (svcs/get-reports (:query-url pdb2) (:certname report))))))))

(deftest end-to-end-factset-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-factset pdb1 facts))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2))

      (is (=-after? without-timestamp
                    (first (svcs/get-factsets (:query-url pdb1) (:certname facts)))
                    (first (svcs/get-factsets (:query-url pdb2) (:certname facts))))))))

(deftest end-to-end-catalog-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-catalog pdb1 catalog))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2))

      (is (=-after? without-timestamp
                    (first (svcs/get-catalogs (:query-url pdb1) (:certname catalog)))
                    (first (svcs/get-catalogs (:query-url pdb2) (:certname catalog))))))))

(deftest deactivate-then-sync
  (let [certname (:certname catalog)]
    (with-pdbs (default-pdb-configs 2)
      (fn [pdb1 pdb2]
        ;; sync a node
        (with-alt-mq (:mq-name pdb1)
          (submit-catalog pdb1 catalog)
          (submit-factset pdb1 facts)
          (submit-report pdb1 report)
          (deactivate-node pdb1 certname))

        (with-alt-mq (:mq-name pdb2)
          (sync :from pdb1 :to pdb2)
          (let [node (get-node (:query-url pdb2) certname)]
            (is (:deactivated node))))))))

(deftest sync-after-deactivate
  (let [certname (:certname catalog)]
    (with-pdbs (default-pdb-configs 2)
      (fn [pdb1 pdb2]
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
            (is (:deactivated node))))))))

(deftest periodic-sync
  (let [sync-interval "2s"]
    (let [periodic-sync-configs
          (fn [infos]
            (case (count infos)
              ;; infos length tells us which server we're handling.
              0 (utils/pdb1-sync-config)
              1 (let [url (base-url->str (:server-url (infos 0)))]
                  (assoc (utils/pdb2-sync-config)
                         :sync {:remotes [{:server_url url
                                           :interval sync-interval}]}))
              nil))
          facts-from #(-> % :query-url (svcs/get-factsets (:certname facts)) first)]
      (with-pdbs periodic-sync-configs
        (fn [master mirror]
          (with-alt-mq (:mq-name master)
            (is (nil? (facts-from mirror)))
            (blocking-command-post (:command-url master) "replace facts" 4 facts)
            @(block-until-results 100 (facts-from master)))
          @(block-until-results 100 (facts-from mirror))
          (is (=-after? without-timestamp
                        (facts-from mirror)
                        (facts-from master))))))))

(deftest pull-record-that-wouldnt-be-expired-locally
  (let [certname (:certname catalog)]
    (with-pdbs (default-pdb-configs 2)
      (fn [pdb1 pdb2]
        ;; add a node to pdb1
        (with-alt-mq (:mq-name pdb1)
          (submit-catalog pdb1 catalog))

        ;; expire it manually
        (jdbc/with-transacted-connection (:db pdb1)
          (scf-store/expire-node! certname))

        (with-alt-mq (:mq-name pdb2)
          (sync :from pdb1 :to pdb2)
          ;; the node shouldn't be expired from pdb2's perspective, so it
          ;; should be pulled.
          (let [node (get-node (:query-url pdb2) certname)]
            (is (not (:expired node)))))))))

(deftest dont-pull-record-that-would-be-expired-locally
  (let [certname (:certname catalog)
        pdb-configs (fn [infos]
                      (case (count infos)
                        ;; infos length tells us which server we're handling.
                        0 (utils/pdb1-sync-config)
                        1 (assoc (utils/pdb2-sync-config)
                                 :node-ttl "1d")
                        nil))]
    (with-pdbs pdb-configs
      (fn [pdb1 pdb2]
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
                        (get-node (:query-url pdb2) certname))))))))

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
        (is (string? (:certname m#)))
        (is (string? (:hash m#)))
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
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
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
              #(verify-entity-sync "start" "catalogs" 0 0 %)
              #(verify-record-sync "start" "catalogs" %)
              #(verify-record-sync "finished" "catalogs" %)
              #(verify-entity-sync "finished" "catalogs" 1 0 %)
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
             events))))))

(deftest sync-logging-entity-failure
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (let [events
            ;; Force a transfer error while syncing a factset.
            (let [log (atom [])]
              (with-log-level "sync" :debug
                (with-logging-to-atom "sync" log
                  (let [certname (:certname catalog)]
                    (with-alt-mq (:mq-name pdb1)
                      (submit-factset pdb1 facts))
                    (with-alt-mq (:mq-name pdb2)
                      (with-redefs [sync-core/query-record-and-transfer!
                                    (fn [& args]
                                      (throw+ {:type ::sync-core/remote-host-error
                                               :error-response {:status 404}}))]
                        (perform-sync (base-url->str (:query-url pdb1))
                                      (str (base-url->str (:sync-url pdb2))
                                           "/trigger-sync")))))))
              (map event->map @log))]
        ;; Check that factsets :failed is 1.
        (is (ordered-matches?
             [#(verify-sync "start" %)
              #(verify-entity-sync "start" "factsets" 0 0 %)
              #(verify-entity-sync "finished" "factsets" 0 1 %)
              #(verify-sync "finished" %)]
             events))))))
