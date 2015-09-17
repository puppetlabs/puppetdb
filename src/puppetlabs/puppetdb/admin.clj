(ns puppetlabs.puppetdb.admin
  (:require [compojure.core :as compojure]
            [compojure.route :as route]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.http :as http]
            [ring.middleware.multipart-params :as mp]
            [clj-time.core :refer [now]]
            [ring.util.io :as rio]))

(def query-api-version :v4)

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
       (compojure/GET "/v1/archive" []
                      (http/streamed-tar-response #(export/export! % query-fn)
                                                  (format "puppetdb-export-%s.tgz" (now))))
       (route/not-found "Not Found"))))
