(ns puppetlabs.pe-puppetdb-extensions.sync.sync-test-utils
  (:refer-clojure :exclude [sync])
  (:require [clj-time.core :as t]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.pe-puppetdb-extensions.sync.services :as services]
            [puppetlabs.pe-puppetdb-extensions.testutils
             :refer [index-by json-response blocking-command-post]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.log :refer [with-log-suppressed-unless-notable]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [slingshot.slingshot :refer [throw+]]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.middleware :as mid]
            [clojure.core.match :as cm]))

(defn notable-pdb-event? [event] true)

;;; Test data

(def facts {:certname "foo.local"
            :environment "DEV"
            :values tuf/base-facts
            :producer_timestamp (new java.util.Date)
            :producer "bar.com"})

(def catalog (assoc (get-in wire-catalogs [9 :basic])
                    :certname "foo.local"))

(def report (-> reports :basic reports/report-query->wire-v8))


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

(defn logging-query-routes
  "Build a handler to stub certain query results. This can handle two kinds of
  queries; first, an 'extract' query, which it assumes to be the summary
  query. Second, a lookup-by-identity query, where the record key is
  `record-identity-key`. (this is either :hash or :certname). The responses are
  generated using the contents of stub-data-atom, which is a seq of summary
  records. Each summary record also has a :content field which is stripped out
  for the summary query and is used for the response of the lookup-by-hash
  query.

  All queries are logged to `requests-atom`."
  [entity-of-interest requests-atom stub-data-atom record-identity-key]
  (cmdi/routes
   (cmdi/GET "/pdb-x/sync/v1/reports-summary" [] (json-response {}))
   (cmdi/GET "/pdb-x/sync/v1/catalogs-summary" [] (json-response {}))
   (cmdi/ANY "/pdb-x/query/v4" request
     (let [query-params (:query-params request)
           stub-data @stub-data-atom
           stub-data-index (index-by record-identity-key stub-data)
           summary-data (map #(select-keys % [:certname :hash :producer_timestamp :transaction_uuid])
                             stub-data)
           record-identity-key-name (name record-identity-key)
           entity-of-interest-name (name entity-of-interest)]
       (when-let [query (or (some-> (query-params "query") json/parse-string vec)
                            (some-> request :body slurp json/parse-string (get "query")))]
         (cm/match [query]
                   [["from" entity-of-interest-name
                     ["extract" & _]]]
                   (do
                     (swap! requests-atom conj query)
                     (json-response summary-data))

                   [["from" entity-of-interest-name
                     ["and" ["in" record-identity-key-name ["array" id-vals]] & _]]]
                   (do
                     (swap! requests-atom conj query)
                     (->> id-vals
                          (map (partial get stub-data-index))
                          json-response))

                   :else
                   (json-response [])))))))

(defn trigger-sync [source-pdb-url dest-sync-url]
 (http/post dest-sync-url
            {:headers {"content-type" "application/json"}
             :body (json/generate-string {:remote_host_path source-pdb-url})
             :as :text}))

(defn pprint-str [x]
  (with-open [writer (java.io.StringWriter.)]
    (pprint x writer)
    (.toString writer)))

(defn perform-sync [source-pdb-url dest-sync-url]
  (let [response (http/post dest-sync-url
                             {:headers {"content-type" "application/json"}
                              :body (json/generate-string {:remote_host_path source-pdb-url})
                              :query-params {"secondsToWaitForCompletion" "15"}
                              :as :text})]
    (when (>= (:status response) 400)
      (log/errorf "Failed to perform blocking sync, response is:\n %s" (pprint-str response))
      (throw+ response "Failed to perform blocking sync"))))


;;; End to end test utils

(defn get-node [base-url certname]
  (svcs/get-json base-url (str "/nodes/" certname)))

(defn submit-catalog [endpoint catalog]
  (blocking-command-post (:command-url endpoint) (:certname catalog)
                         "replace catalog" 9 catalog))

(defn submit-factset [endpoint facts]
  (blocking-command-post (:command-url endpoint) (:certname facts)
                         "replace facts" 5 facts))

(defn submit-report [endpoint report]
  (blocking-command-post (:command-url endpoint) (:certname report)
                         "store report" 8 report))

(defn deactivate-node [endpoint certname]
  (blocking-command-post (:command-url endpoint) certname "deactivate node" 3
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
