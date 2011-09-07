(ns com.puppetlabs.cmdb.core
  (:gen-class)
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [clojure.contrib.logging :as log]
            [clojure.java.jdbc :as sql]
            [clojure.contrib.duck-streams :as ds])
  (:use [clojure.contrib.command-line :only (with-command-line print-help)]))

;; TODO: externalize this into a configuration file
(def *db* {:classname "com.postgresql.Driver"
           :subprotocol "postgresql"
           :subname "//localhost:5432/cmdb"})

(defn catalog-seq
  "Lazy sequence of parsed catalogs loaded from .json files in 'dirname'"
  [dirname]
  (let [files (.listFiles (ds/file-str dirname))]
    (log/info (format "%d files total to parse" (count files)))
    (filter #(not (nil? %)) (pmap cat/parse-from-json-file files))))

(defn initialize-store
  "Eventually code that initializes the DB will go here"
  [])

(defn -main
  [& args]
  (sql/with-connection *db*
    (initialize-store))
  (log/info "Generating and storing catalogs...")
  (let [catalogs       (catalog-seq "/Users/deepak/Desktop/temp")
        handle-catalog (fn [catalog]
                         (log/info (catalog :certname))
                         (sql/with-connection *db*
                           (scf-store/persist-catalog! catalog)))]
    (dorun (map handle-catalog catalogs)))
  (log/info "Done persisting catalogs."))
