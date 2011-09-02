(ns com.puppetlabs.cmdb.core
  (:gen-class)
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [clojure.contrib.logging :as log]
            [clj-json.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.contrib.duck-streams :as ds]
            [digest])
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

(defn persist-certname!
  "Given a certname, persist it in the db"
  [certname]
  (sql/insert-record :certnames {:name certname}))

(defn persist-classes!
  "Given a certname and a list of classes, persist them in the db"
  [certname classes]
  (let [default-row {:certname certname}
        classes     (map #(assoc default-row :name %) classes)]
    (apply sql/insert-records :classes classes)))

(defn persist-tags!
  "Given a certname and a list of tags, persist them in the db"
  [certname tags]
  (let [default-row {:certname certname}
        tags        (map #(assoc default-row :name %) tags)]
    (apply sql/insert-records :tags tags)))

(defn resource-already-persisted?
  "Returns a boolean indicating whether or not the given resource exists in the db"
  [hash]
  (sql/with-query-results result-set
    ["SELECT EXISTS(SELECT 1 FROM resources WHERE hash=?) as present" hash]
    (let [row (first result-set)]
      (row :present))))

(defn compute-hash
  "Compute a hash for a given resource that will uniquely identify it
  within a population."
  [resource]
  {:pre  [resource]
   :post [(string? %)]}
  (-> resource
      (str)
      (digest/sha-1)))

(defn persist-resource!
  "Given a certname and a single resource, persist that resource and its parameters"
  [certname {:keys [type title exported parameters] :as resource}]
  ;; Have to do this to avoid deadlock on updating "resources" and
  ;; "resource_params" tables in the same xaction
  (sql/do-commands "LOCK TABLE resources IN EXCLUSIVE MODE")

  (let [hash       (compute-hash resource)
        persisted? (resource-already-persisted? hash)]

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

    ;; Insert pointer into certname => resource map
    (sql/insert-record :certname_resources {:certname certname :resource hash})))

(defn persist-edges!
  "Persist the given edges in the database

Each edge is looked up in the supplied resources map to find a
resource object that corresponds to the edge. We then use that
resource's hash for persistence purposes.

For example, if the source of an edge is {'type' 'Foo' 'title' 'bar'},
then we'll lookup a resource with that key and use its hash."
  [certname edges resources]
  (let [rows  (for [{:keys [source target relationship]} edges
                    :let [source-hash (compute-hash (resources source))
                          target-hash (compute-hash (resources target))
                          type        (name relationship)]]
                {:certname certname :source source-hash :target target-hash :type type})]
    (apply sql/insert-records :edges rows)))

(defn persist-catalog!
  "Persist the supplied catalog in the database"
  [catalog]
  (let [{:keys [certname resources classes edges tags]} catalog]

    (sql/transaction (persist-certname! certname))
    (sql/transaction (persist-classes! certname classes))
    (sql/transaction (persist-tags! certname tags))
    (doseq [resource (vals resources)]
      (sql/transaction (persist-resource! certname resource)))
    (sql/transaction (persist-edges! certname edges resources))))

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
