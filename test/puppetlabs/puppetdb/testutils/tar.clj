(ns puppetlabs.puppetdb.testutils.tar
  (:import  [puppetlabs.puppetdb.archive TarGzReader]
            [org.apache.commons.compress.archivers.tar TarArchiveEntry])
  (:require [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.client :as client]
            [clj-http.client :as http-client]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils
             :refer [base-url-schema export-root-dir]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [clj-time.core :refer [now]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn-validated process-tar-entry
  [tar-reader :- TarGzReader
   acc
   tar-entry :- TarArchiveEntry]
  (let [path (.getName tar-entry)]
    (condp re-find path
      (import/file-pattern "catalogs")
      (let [catalogs (-> (archive/read-entry-content tar-reader)
                         (json/parse-string true))]
        (update-in acc [:catalogs (:certname catalogs)] conj catalogs))
      (import/file-pattern "reports")
      (let [reports (-> (archive/read-entry-content tar-reader)
                        (json/parse-string true))]
        (update-in acc [:reports (:certname reports)] conj reports))
      (import/file-pattern "facts")
      (let [facts (-> (archive/read-entry-content tar-reader)
                      (json/parse-string true))]
        (update-in acc [:facts (:certname facts)] conj facts))
      (import/file-pattern "export-metadata")
      (let [metadata (-> (archive/read-entry-content tar-reader)
                         (json/parse-string true))]
        (assoc acc :metadata metadata))
      acc)))

(defn archive->map
  [file]
  (with-open [tar-reader (archive/tarball-reader file)]
    (reduce (partial process-tar-entry tar-reader) {} (archive/all-entries tar-reader))))
