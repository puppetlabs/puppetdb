(ns puppetlabs.puppetdb.http.reports
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.events :as e]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  [version]
  (app
    [hash "events" &]
    (let [projs ["new_value" "property" "file" "old_value"
                 "containing_class" "line" "resource_type"
                 "status" "resource_title" "timestamp"
                 "containment_path" "message" ]]
      (comp (e/events-app version) (partial http-q/project-query projs) (partial http-q/restrict-query-to-report hash)))
    []
    {:get  (fn [{:keys [params globals paging-options] :as request}]
             (produce-streaming-body
               :reports
               version
               (params "query")
               paging-options
               (:scf-read-db globals)))}))

(defn reports-app
  [version]
  (-> (query-app version)
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options
      verify-accepts-json))
