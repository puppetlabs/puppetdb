(ns com.puppetlabs.puppetdb.http.v3.facts
  (:require [com.puppetlabs.puppetdb.http.v2.facts :as v2-facts]))

(def facts-app
  v2-facts/facts-app)
