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
  "Parse a JSON catalog located at 'filename', returning a map

This func will actually only return the 'data' key of the catalog,
which seems to contain the useful stuff."
  [filename]
  (try
    (let [catalog (json/parse-string (slurp filename))]
      (catalog "data"))
    (catch org.codehaus.jackson.JsonParseException e
      (log/error (format "Error parsing %s: %s" filename (.getMessage e))))))

(defn catalog-seq
  "Lazy sequence of parsed catalogs loaded from .json files in 'dirname'"
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
  ;; Have to do this to avoid deadlock on updating "resources" and
  ;; "resource_params" tables in the same xaction
  (sql/do-commands "LOCK TABLE resources IN EXCLUSIVE MODE")

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

(defn persist-catalog!
  "Persist the supplied catalog in the database"
  [catalog]
  (let [{host "name" resources "resources" edges "edges"} catalog]

    (sql/transaction (persist-hostname! host catalog))
    (sql/transaction (persist-classes! host catalog))
    (sql/transaction (persist-edges! host catalog))
    (doseq [resource resources]
      (sql/transaction (persist-resource! host resource)))))

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
                         (log/info (catalog "name"))
                         (sql/with-connection *db*
                           (persist-catalog! catalog)))]
    (dorun (pmap handle-catalog catalogs)))
  (log/info "Done persisting catalogs."))
