(ns puppetlabs.puppetdb.testutils.http
  (:require [clj-http.client :as client]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.utils :as utils]
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
