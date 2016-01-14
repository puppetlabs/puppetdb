(ns puppetlabs.puppetdb.admin
  (:require [compojure.core :as compojure]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.http :as http]
            [ring.middleware.multipart-params :as mp]
            [clj-time.core :refer [now]]
            [ring.util.io :as rio]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.middleware :as mid]
            [bidi.schema :as bidi-schema]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(pls/defn-validated admin-routes :- bidi-schema/RoutePair
  [submit-command-fn :- (s/pred fn?)
   query-fn :- (s/pred fn?)]
  (cmdi/context "/v1/archive"
                (cmdi/wrap-routes
                 (cmdi/POST "" request
                            (let [{{:strs [archive command_versions]} :multipart-params} request]
                              (import/import! (:tempfile archive)
                                              (json/parse-string command_versions true)
                                              submit-command-fn)
                              (http/json-response {:ok true})))
                 mp/wrap-multipart-params)
                (cmdi/GET "" [anonymization_profile]
                          (http/streamed-tar-response #(export/export! % query-fn anonymization_profile)
                                                      (format "puppetdb-export-%s.tgz" (now))))))

(defn build-app
  [submit-command-fn query-fn]
  (mid/make-pdb-handler (admin-routes submit-command-fn query-fn)))
