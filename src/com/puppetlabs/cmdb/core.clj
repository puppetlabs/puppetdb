(ns com.puppetlabs.cmdb.core
  (:gen-class)
  (:require [clojure.contrib.logging :as log]
            [clj-json.core :as json]
            [clojure.java.jdbc :as sql])
  (:use [clojure.contrib.command-line :only (with-command-line print-help)]))

;; TODO: externalize this into a configuration file
(def *db* {:classname "com.postgresql.Driver"
           :subprotocol "postgresql"
           :subname "//localhost:5432/cmdb"})

(defn tweak-catalog
  "Takes an existing catalog, and returns a copy with a new name and
some data munged"
  [catalog]
  (let [name (get-in catalog ["data" "name"])
        new-name (inc (Integer/parseInt name))]
    (assoc-in catalog ["data" "name"] (.toString new-name))))
  
(defn random-catalog-seq
  "Reads in an exmplar catalog in json format, and synthesizes an
infiinte sequence of catalog objects (json objects) that are similar"
  [exemplar-catalog]
  (let [exemplar-json (json/parse-string (slurp exemplar-catalog))
        initial-catalog (assoc-in exemplar-json ["data" "name"] "0")]
    (iterate tweak-catalog initial-catalog)))

(defn transform-catalog
  "Take a JSON catalog and transform it to persistable form"
  [catalog]
  (catalog "data"))

(defn persist-hostname!
  "Given a host and a catalog, persist the hostname"
  [host catalog]
  (sql/insert-record :hosts {:name host}))

(defn persist-classes!
  "Given a host and a catalog, persist the list of classes applicable to the host"
  [host catalog]
  (let [classes (set (catalog "classes"))
        default-row {:host host}
        classes (map #(assoc default-row :name %) classes)]
    (apply sql/insert-records :classes classes)))

(defn persist-resource!
  "Given a host and a single resource, persist that resource and its parameters"
  [host resource]
  (let [type (resource "type")
        title (resource "title")
        exported (resource "exported")
        row {:host host :type type :title title :exported exported}

        ;; Add a single row for the main resource, and capture the id
        {id :id} (sql/insert-record :resources row)]

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
                      {:id id :name name :value value}))]
      ;; ...and insert them
      (apply sql/insert-records :resource_params records))

    {[type title] id}))

(defn persist-edges!
  [host catalog resources-to-ids]
  (let [edges (catalog "edges")
        extract (fn [s] (let [[_ type title] (re-find #"(^.*)\[(.*)\]" s)]
                          [type title]))
        rows (for [{source "source" target "target"} edges]
               (let [source-id (resources-to-ids (extract source))
                     target-id (resources-to-ids (extract target))]
                     {:source source-id :target target-id}))]
        (apply sql/insert-records :edges rows)))

(defn persist-catalog!
  "Persist the supplied catalog in the database"
  [catalog]
  (sql/transaction
   ;; I think we can live with a commit delay, for better performance
   (sql/do-commands "SET LOCAL synchronous_commit TO OFF")
   (let [catalog (transform-catalog catalog)
         host (catalog "name")
         resources (catalog "resources")
         resources-to-ids (transient {})]

     (persist-hostname! host catalog)
     (persist-classes! host catalog)
     (doseq [resource resources]
       (let [resource-id-info (persist-resource! host resource)]
         (conj! resources-to-ids resource-id-info)))

     (let [resources-to-ids (persistent! resources-to-ids)]
       (persist-edges! host catalog resources-to-ids)))))

(defn initialize-store
  "Eventually code that initializes the DB will go here"
  [])
  
(defn -main
  [& args]
  (sql/with-connection *db*
    (initialize-store))
  (println "Generating and storing catalogs...")
  (let [catalogs (take 1000 (random-catalog-seq "/Users/deepak/Desktop/out.json"))
        handle-catalog (fn [catalog]
                         (println (get-in catalog ["data" "name"]))
                         (sql/with-connection *db*
                           (persist-catalog! catalog)))]
    (dorun (map handle-catalog catalogs)))
  (println "Done persisting catalogs."))
