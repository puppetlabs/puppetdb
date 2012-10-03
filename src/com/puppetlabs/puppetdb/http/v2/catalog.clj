(ns com.puppetlabs.puppetdb.http.v2.catalog
  (:require  [com.puppetlabs.puppetdb.http.v1.catalog :as v1-catalog]))

(def catalog-app
  v1-catalog/catalog-app)
