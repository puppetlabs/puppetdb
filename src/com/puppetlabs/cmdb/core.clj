(ns com.puppetlabs.cmdb.core
  (:gen-class)
  (:require [clojure.contrib.logging :as log]
            [clj-json.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.contrib.duck-streams :as ds]
            [digest])
  (:use [clojure.contrib.command-line :only (with-command-line print-help)]))

;; TODO: externalize this into a configuration file
(def *db* {:classname "com.postgresql.Driver"
           :subprotocol "postgresql"
           :subname "//localhost:5432/cmdb2"})

(defn parse-catalog
  [filename]
  (try
    (let [catalog (json/parse-string (slurp filename))]
      (catalog "data"))
    (catch org.codehaus.jackson.JsonParseException e
      (log/error (format "Error parsing %s: %s" filename (.getMessage e))))))

(defn catalog-seq
  [dirname]
  (let [files (.listFiles (ds/file-str dirname))]
    (log/info (format "%d files total to parse" (count files)))
    (filter #(not (nil? %)) (pmap parse-catalog files))))

(defn persist-hostname!
  "Given a host and a catalog, persist the hostname"
  [host catalog]
  (sql/insert-record :hosts {:name host}))

(defn persist-classes!
  "Given a host and a catalog, persist the list of classes applicable to the host"
  [host catalog]
  (let [classes     (set (catalog "classes"))
        default-row {:host host}
        classes     (map #(assoc default-row :name %) classes)]
    (apply sql/insert-records :classes classes)))

(defn resource-already-persisted?
  "Returns a boolean indicating whether or not the given resource exists in the db"
  [hash]
  (sql/with-query-results result-set
    ["SELECT EXISTS(SELECT 1 FROM resources WHERE hash=?) as present" hash]
    (let [row (first result-set)]
      (row :present))))

(defn persist-resource!
  "Given a host and a single resource, persist that resource and its parameters"
  [host resource]
  (sql/do-commands "LOCK TABLE resources")
  (let [hash       (digest/sha-1 (json/generate-string resource))
        type       (resource "type")
        title      (resource "title")
        exported   (resource "exported")
        persisted? (resource-already-persisted? hash)]

    (when-not persisted?
      ;; Add to resources table
      (sql/insert-record :resources {:hash hash :type type :title title :exported exported})

      ;; Build up a list of records for insertion
      (let [records (for [[name value] (resource "parameters")]
                      ;; I'm not sure what to do about multi-value columns.
                      ;; I suppose that we could put them in as an array of values,
                      ;; but then I think we'll have to call out directly to the JDBC
                      ;; driver as most ORM layers don't support arrays (clojure.core/sql
                      ;; included)
                      (let [value (if (coll? value)
                                    (json/generate-string value)
                                    value)]
                        {:resource hash :name name :value value}))]

        ;; ...and insert them
        (apply sql/insert-records :resource_params records)))

    ;; Insert pointer into host => resource map
    (sql/insert-record :host_resources {:host host :resource hash})))

(defn persist-edges!
  [host catalog]
  (let [edges (catalog "edges")
        rows  (for [{source "source" target "target"} edges]
                {:host host :source source :target target})]
        (apply sql/insert-records :edges rows)))

(defn persist-catalog!*
  "Persist the supplied catalog in the database"
  [catalog]
  (sql/transaction
   ;; I think we can live with a commit delay, for better performance
   (sql/do-commands "SET LOCAL synchronous_commit TO OFF")

   (let [{host "name" resources "resources" edges "edges"} catalog]

     (persist-hostname! host catalog)
     (persist-classes! host catalog)
     (doseq [resource resources] (persist-resource! host resource))
     (persist-edges! host catalog))))

(defn persist-catalog!
  "Persist the supplied catalog, returning boolean indicating if
caller must retry the operation"
  [catalog]
  (try
    (sql/with-connection *db*
      (persist-catalog!* catalog))
    ;; Return nil, signalling no need to retry
    nil
    ;; TODO Replace broad exception catch with fn that unwinds chain to find root cause
    (catch Exception e
      (if (re-find #"PSQLException: .* deadlock detected" (.getMessage e))
        true
        (throw e)))))

(defn initialize-store
  "Eventually code that initializes the DB will go here"
  [])
  
(defn -main
  [& args]
  (sql/with-connection *db*
    (initialize-store))
  (log/info "Generating and storing catalogs...")
  (let [catalogs       (catalog-seq "/Users/deepak/Desktop/many_catalogs")
        handle-catalog (fn [catalog]
                         (loop []
                           (log/info (catalog "name"))
                           (when (persist-catalog! catalog)
                             (log/error (format "Retrying %s" (catalog "name")))
                             (Thread/sleep 1000)
                             (recur))))]
    (dorun (pmap handle-catalog catalogs)))
  (log/info "Done persisting catalogs."))
