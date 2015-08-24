(ns puppetlabs.puppetdb.admin
  (:require [clojure.java.io :as io]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.anonymize :as cli-anon]
            [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [ring.middleware.multipart-params :as mp]
            [clojure.string :as str]
            [clj-time.core :refer [now]]
            [ring.util.io :as rio]))

(def query-api-version :v4)

(defn munge-nodes-data
  [nodes]
  (as-> nodes $
    (select-keys $ [:certname :facts_timestamp :catalog_timestamp :report_timestamp])
    (kitchensink/mapvals str $)))

(defn anonymize-data
  [anonymization-config anonymize-fn data]
  (if (nil? anonymization-config)
    data
    (->> data
         walk/stringify-keys
         (map #(anonymize-fn anonymization-config %)))))

(defn export-app
  [buffer query-fn anonymization-config]
  (let [tar-items-lists (for [[entity get-data-fn anonymize-fn]
                              [["facts" export/facts-for-query anon/anonymize-facts]
                               ["catalogs" export/catalogs-for-query anon/anonymize-catalog]
                               ["reports" export/reports-for-query anon/anonymize-report]]
                              :let [data (->> (get-data-fn query-fn nil)
                                              (anonymize-data anonymization-config anonymize-fn))]
                              :when (not (empty? data))]
                          (map #(export/data->tar entity %) data))
        tar-items (apply concat
                         [(export/export-metadata (now))]
                         tar-items-lists)]
    (export/export! buffer tar-items)))

(defn build-app
  [submit-command-fn query-fn]
  (-> (compojure/routes
       (mp/wrap-multipart-params
        (compojure/POST "/v1/archive" request
                        (let [{{:strs [archive command_versions]} :multipart-params} request]
                          (import/import! (:tempfile archive)
                                          (json/parse-string command_versions true)
                                          submit-command-fn)
                            (http/json-response {:ok true}))))
       (compojure/GET "/v1/archive" {:keys [params]}
                      (let [anonymization-profile (get params "anonymization_profile" ::not-found)
                            anonymization-config (get cli-anon/anon-profiles anonymization-profile)]
                        (http/streamed-tar-response #(export-app % query-fn anonymization-config)
                                                    (format "puppetdb-export-%s.tgz" (now)))))
       (route/not-found "Not Found"))))
