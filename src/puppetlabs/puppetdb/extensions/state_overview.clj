(ns puppetlabs.puppetdb.extensions.state-overview
  (:require [puppetlabs.puppetdb.cheshire :as json]))

(defn state-overview-app
  [{:keys [globals] :as request}]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
           {:failed 0 :unchanged 0 :unresponsive 0 :noop 0 :success 0 :unreported 0})})
