(ns puppetlabs.puppetdb.testutils.http
  (:require [clj-http.client :as client]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]

            [puppetlabs.puppetdb.cheshire :as json]))

(defn pdb-get
  "Makes a GET reqeust using to the PuppetDB instance at `base-url`
  with `url-suffix`. Will parse the body of the response if it has a
  json content type."
  [base-url url-suffix]
  (let [resp (client/get (str (utils/base-url->str base-url)
                              url-suffix)
                         {:throw-exceptions false})]
    (if (tu/json-content-type? resp)
      (update resp :body #(json/parse-string % true))
      resp)))

(defn query-response
  ([method endpoint]      (query-response method endpoint nil))
  ([method endpoint query] (query-response method endpoint query {}))
  ([method endpoint query params]
   (fixt/*app* (tu/query-request method endpoint query {:params params}))))

(defn order-param
  [method order-by]
  (if (= :get method)
    (json/generate-string order-by)
    order-by))

(defn query-result
  ([method endpoint] (query-result method endpoint nil))
  ([method endpoint query] (query-result method endpoint query {}))
  ([method endpoint query params & optional-handlers]
   (let [handlers (or optional-handlers [identity])
         handle-fn (apply comp (vec handlers))
         response (query-response method endpoint query params)]
     (is (= http/status-ok (:status response)))
     (-> response
         :body
         slurp
         (json/parse-string true)
         handle-fn
         set))))

