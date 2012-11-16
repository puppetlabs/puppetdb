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
  "Get the version number of this PuppetDB installation."
  []
  {:post [(string? %)]}
  (version/get-version "puppetdb" "puppetdb"))

(def version
  "Get the version number of this PuppetDB installation."
  (memoize version*))

;; ### Utility functions for checking for the latest available version of PuppetDB

(defn version-data*
  "Build up a map of version data to be used in the 'latest version' check.

  `db` is a map containing the database connection info, in the format
  used by `clojure.jdbc`."
  [db]
  {:pre  [(map? db)]
   :post [(map? %)]}
  (sql/with-connection db
    {:database-name    (scf-store/sql-current-connection-database-name)
     :database-version (string/join "." (scf-store/sql-current-connection-database-version))}))

(def version-data
  "Build up a map of version data to be used in the 'latest version' check."
  (memoize version-data*))

(defn update-info
  "Make a request to the puppetlabs server to determine the latest available
  version of PuppetDB.  Returns the JSON object received from the server, which
  is expected to be a map containing keys `:version`, `:newer`, and `:link`.

  Returns `nil` if the request does not succeed for some reason."
  [update-server db]
  {:pre  [(string? update-server)
          (map? db)]
   :post [((some-fn map? nil?) %)]}
  (let [current-version        (version)
        version-data           (assoc (version-data db) :version current-version)
        query-string           (ring-codec/form-encode version-data)
        url                    (format "%s?product=puppetdb&%s" update-server query-string)
        {:keys [status body]}  (client/get url {:throw-exceptions false
                                                :accept           :json})]
    (when (= status 200)
      (json/parse-string body true))))
