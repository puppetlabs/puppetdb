(ns com.puppetlabs.cmdb.core
  (:gen-class)
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [clojure.contrib.logging :as log]
            [clj-json.core :as json]
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

(defn persist-hostname!
  "Given a hostname, persist it in the db"
  [host]
  (sql/insert-record :hosts {:name host}))

(defn persist-classes!
  "Given a host and a list of classes, persist them in the db"
  [host classes]
  (let [default-row {:host host}
        classes     (map #(assoc default-row :name %) classes)]
    (apply sql/insert-records :classes classes)))

(defn persist-tags!
  "Given a host and a list of tags, persist them in the db"
  [host tags]
  (let [default-row {:host host}
        tags        (map #(assoc default-row :name %) tags)]
    (apply sql/insert-records :tags tags)))

(defn resource-already-persisted?
  "Returns a boolean indicating whether or not the given resource exists in the db"
  [hash]
  (sql/with-query-results result-set
    ["SELECT EXISTS(SELECT 1 FROM resources WHERE hash=?) as present" hash]
    (let [row (first result-set)]
      (row :present))))

(defn persist-resource!
  "Given a host and a single resource, persist that resource and its parameters"
  [host {:keys [type title hash exported parameters] :as resource}]
  ; Have to do this to avoid deadlock on updating "resources" and
  ; "resource_params" tables in the same xaction
  (sql/do-commands "LOCK TABLE resources IN EXCLUSIVE MODE")

  (let [persisted? (resource-already-persisted? hash)]

    (when-not persisted?
      ; Add to resources table
      (sql/insert-record :resources {:hash hash :type type :title title :exported exported})

      ; Build up a list of records for insertion
      (let [records (for [[name value] parameters]
                      ; I'm not sure what to do about multi-value columns.
                      ; I suppose that we could put them in as an array of values,
                      ; but then I think we'll have to call out directly to the JDBC
                      ; driver as most ORM layers don't support arrays (clojure.core/sql
                      ; included)
                      (let [value (if (coll? value)
                                    (json/generate-string value)
                                    value)]
                        {:resource hash :name name :value value}))]

        ; ...and insert them
        (apply sql/insert-records :resource_params records)))

    ; Insert pointer into host => resource map
    (sql/insert-record :host_resources {:host host :resource hash})))

(defn persist-edges!
  "Persist the given edges in the database

Each edge is looked up in the supplied resources map to find a
resource object that corresponds to the edge. We then use that
resource's hash for persistence purposes.

For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
then we'll lookup a resource with that key and use its hash."
  [host edges resources]
  (let [rows  (for [{:keys [source target relationship]} edges
                    :let [source-hash (get-in resources [source :hash])
                          target-hash (get-in resources [target :hash])
                          type        (name relationship)]]
                {:host host :source source-hash :target target-hash :type type})]
    (apply sql/insert-records :edges rows)))

(defn persist-catalog!
  "Persist the supplied catalog in the database"
  [catalog]
  (let [{:keys [host resources classes edges tags]} catalog]

    (sql/transaction (persist-hostname! host))
    (sql/transaction (persist-classes! host classes))
    (sql/transaction (persist-tags! host tags))
    (doseq [resource (vals resources)]
      (sql/transaction (persist-resource! host resource)))
    (sql/transaction (persist-edges! host edges resources))))

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
                         (log/info (catalog "name"))
                         (sql/with-connection *db*
                           (persist-catalog! catalog)))]
    (dorun (pmap handle-catalog catalogs)))
  (log/info "Done persisting catalogs."))
