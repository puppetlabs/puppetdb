(ns com.puppetlabs.puppetdb.http.v2.facts
  (:require  [com.puppetlabs.puppetdb.http.v1.facts :as v1-facts]))

(def facts-app
  v1-facts/facts-app)
