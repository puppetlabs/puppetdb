(ns com.puppetlabs.cmdb.test.query
  (:require [com.puppetlabs.cmdb.query :as query]
            [clj-json.core :as json]
            ring.mock.request)
  (:use [clojure.test]))

(def default-headers {"Accept" "application/json"})
(defn request
  ([method path]
     (request method path {}))
  ([method path headers]
     (query/ring-handler (ring.mock.request/request
                          method path (merge default-headers headers)))))
