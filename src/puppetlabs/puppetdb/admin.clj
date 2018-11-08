(ns puppetlabs.puppetdb.admin
  (:require [compojure.core :as compojure]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.http :as http]
            [ring.middleware.multipart-params :as mp]
            [puppetlabs.puppetdb.query.summary-stats :as ss]
            [puppetlabs.puppetdb.time :refer [now]]
            [ring.util.io :as rio]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.middleware :as mid]
            [bidi.schema :as bidi-schema]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+]]))

(def clean-command-schema
  {:command (s/eq "clean")
   :version (s/eq 1)
   :payload cli-svc/clean-request-schema})

(def ^:private validate-clean-command (s/validator clean-command-schema))

(defn- invalid-clean-command-info [command]
  (try+
   (validate-clean-command command)
   nil
   (catch [:type :schema.core/error] ex
     ex)))

(defn- handle-clean-req [req clean]
  (let [body (slurp (:body req))
        command (json/parse-string body true)
        invalid-info (invalid-clean-command-info command)]
    (cond
      invalid-info
      (http/bad-request-response (i18n/tru "Invalid command {0}: {1}"
                                           (pr-str body)
                                           (pr-str (:error invalid-info))))

      (deref (future (clean (:payload command))))
      (http/json-response {:ok true})

      :else
      (http/json-response
       {:kind "conflict"
        :msg (i18n/tru "Another cleanup is already in progress")
        :details nil}
       http/status-conflict))))

(pls/defn-validated admin-routes :- bidi-schema/RoutePair
  [submit-command-fn :- (s/pred fn?)
   query-fn :- (s/pred fn?)
   get-shared-globals :- (s/pred fn?)
   clean :- (s/pred fn?)]
  (cmdi/context "/v1"
                (cmdi/context "/archive"
                              (cmdi/wrap-routes
                                (cmdi/POST "" request
                                           (import/import! (get-in request [:multipart-params "archive" :tempfile])
                                                           submit-command-fn)
                                           (http/json-response {:ok true}))
                                mp/wrap-multipart-params)
                              (cmdi/GET "" [anonymization_profile]
                                        (http/streamed-tar-response #(export/export! % query-fn anonymization_profile)
                                                                    (format "puppetdb-export-%s.tgz" (now)))))
                (cmdi/context "/cmd"
                              (cmdi/POST "" request
                                         (handle-clean-req request clean)))
                (cmdi/ANY "/summary-stats" []
                          (ss/collect-metadata get-shared-globals))))

(defn build-app
  [submit-command-fn query-fn get-shared-globals clean]
  (mid/make-pdb-handler (admin-routes submit-command-fn
                                      query-fn
                                      get-shared-globals
                                      clean)))
