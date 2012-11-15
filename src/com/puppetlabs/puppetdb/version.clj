;; ## Versioning Utility Library
;;
;; This namespace contains some utility functions relating to checking version
;; numbers of various fun things.

(ns com.puppetlabs.puppetdb.version
  (:require [trptcolin.versioneer.core :as version]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clj-http.client :as client]
            [ring.util.codec :as ring-codec]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]))

;; ### PuppetDB current version

(defn version*
  []
  (version/get-version "puppetdb" "puppetdb"))

(def version
  (memoize version*))

;; ### Utility functions for checking for the latest available version of PuppetDB

(defn version-data*
  "Build up a map of version data to be used in the 'latest version' check."
  [db]
  (sql/with-connection db
    {:database-name (scf-store/sql-current-connection-database-name)
     :database-version (string/join "." (scf-store/sql-current-connection-database-version))}))

(def version-data
  (memoize version-data*))

(defn update-info
  [update-server db]
  (let [current-version        (version)
        version-data           (assoc (version-data db) :version current-version)
        query-string           (ring-codec/form-encode version-data)
        url                    (format "%s?product=puppetdb&%s" update-server query-string)
        {:keys [status body]}  (client/get url {:throw-exceptions false
                                                :accept           :json})]
    (when (= status 200)
      (json/parse-string body true))))
