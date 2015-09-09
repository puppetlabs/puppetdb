(ns puppetlabs.pe-puppetdb-extensions.sync.sync-test-utils
  (:refer-clojure :exclude [sync])
  (:require [clj-time.core :as t]
            [compojure.core :refer [routes GET context]]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.pe-puppetdb-extensions.sync.services :as services]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils :refer [with-puppetdb-instance index-by json-response
                                                                           get-json blocking-command-post]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.services :as cli-svcs]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.log :refer [with-log-suppressed-unless-notable notable-pdb-event?]]
            [puppetlabs.puppetdb.testutils :refer [without-jmx]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]))

;;; Test data

(def facts {:certname "foo.local"
            :environment "DEV"
            :values tuf/base-facts
            :producer_timestamp (new java.util.Date)})

(def catalog (assoc (get-in wire-catalogs [6 :basic])
                    :certname "foo.local"))

(def report (-> reports :basic tur/munge-example-report-for-storage))


;;; General test utils

(defn run-test-for-var
  "Depending on how you're running your tests, it can be tricky to invoke
  another test (lein can yank them out from under you). This should do it more
  reliably."
  [test-var]
  (let [m (meta test-var)
        f (or (:test m) (:leiningen/skipped-test m))]
    (if f
      (f)
      (throw (Exception. (str "Couldn't find a test fn attached to var " (:name meta)))))))

(defmacro with-alt-mq [mq-name & body]
  `(with-redefs [puppetlabs.puppetdb.cli.services/mq-endpoint ~mq-name]
     (do ~@body)))

(defn logging-query-handler
  "Build a handler to stub certain query results. This can handle two kinds of
  queries; first, an 'extract' query, which it assumes to be the summary
  query. Second, a lookup-by-identity query, where the record key is
  `record-identity-key`. (this is either :hash or :certname). The responses are
  generated using the contents of stub-data-atom, which is a seq of summary
  records. Each summary record also has a :content field which is stripped out
  for the summary query and is used for the response of the lookup-by-hash
  query.

  All queries are logged to `requests-atom`."
  [path requests-atom stub-data-atom record-identity-key]
  (routes (GET path {query-params :query-params}
               (let [stub-data @stub-data-atom
                     stub-data-index (index-by record-identity-key stub-data)
                     summary-data (map #(select-keys % [:certname :hash :producer_timestamp]) stub-data)]
                 (when-let [query (vec (json/parse-string (query-params "query")))]
                   (swap! requests-atom conj query)
                   (cond
                     (= "extract" (first query))
                     (json-response summary-data)

                     (and (= "and") (first query)
                          (= ["=" (name record-identity-key)] (take 2 (second query))))
                     (let [[_ [_ _ record-hash]] query]
                       (json-response [(get stub-data-index record-hash)]))))))

          ;; fallback routes, for data that wasn't explicitly stubbed
          (context "/pdb-x/v4" []
                   (GET "/reports" [] (json-response []))
                   (GET "/factsets" [] (json-response []))
                   (GET "/catalogs" [] (json-response []))
                   (GET "/nodes" [] (json-response [])))))

(defn trigger-sync [source-pdb-url dest-sync-url]
 (http/post dest-sync-url
            {:headers {"content-type" "application/json"}
             :body (json/generate-string {:remote_host_path source-pdb-url})
             :as :text}))

(defn perform-sync [source-pdb-url dest-sync-url]
  (let [response (http/post dest-sync-url
                             {:headers {"content-type" "application/json"}
                              :body (json/generate-string {:remote_host_path source-pdb-url})
                              :query-params {"secondsToWaitForCompletion" "5"}
                              :as :text})]
    (if (>= (:status response) 400)
      (throw (ex-info "Failed to perform blocking sync" {:response response})))))


;;; End to end test utils

(defn get-reports [base-url certname]
  (first (get-json base-url "/reports"
                   {:query-params {:query (json/generate-string [:= :certname certname])}})))

(defn get-factset [base-url certname]
  (first (get-json base-url "/factsets"
                   {:query-params {:query (json/generate-string [:= :certname certname])}})))

(defn get-catalog [base-url certname]
  (first (get-json base-url "/catalogs"
                   {:query-params {:query (json/generate-string [:= :certname certname])}})))

(defn get-node [base-url certname]
  (get-json base-url (str "/nodes/" certname)))

(defn submit-catalog [endpoint catalog]
  (blocking-command-post (:command-url endpoint) "replace catalog" 6 catalog))

(defn submit-factset [endpoint facts]
  (blocking-command-post (:command-url endpoint) "replace facts" 4 facts))

(defn submit-report [endpoint report]
  (blocking-command-post (:command-url endpoint) "store report" 5 report))

(defn deactivate-node [endpoint certname]
  (blocking-command-post (:command-url endpoint) "deactivate node" 3
                         {:certname certname
                          :producer_timestamp (t/plus (t/now) (t/years 10))}))

(defn start-sync [& {:keys [from to]}]
  ;; Initiate pull
  (trigger-sync (base-url->str (:query-url from))
                (str (base-url->str (:sync-url to)) "/trigger-sync")))

(defn sync [& {:keys [from to check-with check-for] :as args}]
  (perform-sync (base-url->str (:query-url from))
                (str (base-url->str (:sync-url to)) "/trigger-sync")))

(defn without-timestamp [record]
  (dissoc record :timestamp))

(defn with-pdbs
  "Repeatedly call (gen-config [previously-started-instance-info...])
  and start a pdb instance for each returned config.  When gen-config
  returns false, call (f instance-1-info instance-2-info...).
  Suppress the log unless something \"notable\" happens."
  [gen-config f]
  (letfn [(spawn-pdbs [infos]
            (if-let [config (gen-config infos)]
              (let [mq-name (str "puppetlabs.puppetdb.commands-"
                                 (inc (count infos)))]
                (with-alt-mq mq-name
                  (with-puppetdb-instance config
                    (spawn-pdbs (conj infos
                                      (let [db (-> svcs/*server*
                                                   (get-service :PuppetDBServer)
                                                   service-context
                                                   :shared-globals
                                                   :scf-write-db)]
                                        {:mq-name mq-name
                                         :config config
                                         :server svcs/*server*
                                         :db db
                                         :query-fn (partial cli-svcs/query (tk-app/get-service svcs/*server* :PuppetDBServer))
                                         :server-url svcs/*base-url*
                                         :query-url (utils/pdb-query-url)
                                         :command-url (utils/pdb-cmd-url)
                                         :sync-url (utils/sync-url)}))))))
              (apply f infos)))]
    (with-log-suppressed-unless-notable notable-pdb-event?
      (without-jmx
       (spawn-pdbs [])))))

(defn default-pdb-configs [_]
  (fn [infos]
    (let [num-configs-so-far (count infos)]
      (case num-configs-so-far
        0 (utils/pdb1-sync-config)
        1 (utils/pdb2-sync-config)
        nil))))
