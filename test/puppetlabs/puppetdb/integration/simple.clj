(ns puppetlabs.puppetdb.integration.simple
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [me.raynes.fs :as fs]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.integration.protocols :refer [info-map]]
            [puppetlabs.puppetdb.testutils.db :as dbutils]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.config.typesafe :as hocon]
            [puppetlabs.puppetdb.command :as dispatch]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.testutils :as tu])
  (:import [puppetlabs.puppetdb.integration.protocols TestServer]
           [com.typesafe.config ConfigValueFactory]))

;;; Postgres fixture

(defrecord PostgresTestServer [db-config]
  TestServer
  (info-map [_] db-config)

  java.lang.AutoCloseable
  (close [_] (dbutils/drop-test-db db-config)))

(defn setup-postgres []
  (let [db-config (dbutils/create-temp-db)]
    (PostgresTestServer. db-config)))

;;; PuppetDB fixture

(defrecord PuppetDBTestServer [-info-map app]
  TestServer
  (info-map [_] -info-map)

  java.lang.AutoCloseable
  (close [_] (tk-app/stop app)))

(defn stringify-keys-with-ns [m]
  (let [f (fn [[k v]] (if (keyword? k) [(subs (str k) 1) v] [k v]))]
    ;; only apply to maps
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

;; TODO: Get this fixed version into the clj-typesafe-config library and use it
;; from there
(defn map->hocon-string
  "Serialize the clojure data structure `m` to string using the
  typesafe config format. `m` is typically the result of a
  `config-file->map` or `reader-map` with some modifications."
  [m]
  (-> m
      stringify-keys-with-ns
      ConfigValueFactory/fromAnyRef
      .render))

(defn adjust-config-file [in-file out-file overrides]
  (spit out-file (-> (hocon/config-file->map in-file)
                     (ks/deep-merge overrides)
                     map->hocon-string)))

(defn start-test-puppetdb [bootstrap-config-file config-file config-overrides bind-attempts]
  (when (zero? bind-attempts)
    (throw (RuntimeException. "Repeated attempts to bind port failed, giving up")))
  (let [port (svc-utils/open-port-num)
        tmp-puppetdb-conf (str (tu/temp-file "puppetdb" ".conf"))
        _ (adjust-config-file config-file tmp-puppetdb-conf
                              (-> config-overrides
                                  (assoc-in [:jetty :ssl-port] port)
                                  (assoc-in [:global :vardir] (str (tu/temp-dir)))))
        base-url {:protocol "https"
                  :host "localhost"
                  :port port
                  :prefix "/pdb/query"
                  :version :v4}]
    (try
      (swap! dispatch/metrics svc-utils/clear-counters!)
      {:app (tkbs/parse-and-bootstrap bootstrap-config-file
                                      {:config tmp-puppetdb-conf})
       :base-url base-url}

      (catch java.net.BindException e
        (log/errorf e "Error occured when Jetty tried to bind to port %s, attempt #%s"
                    port bind-attempts)
        (start-test-puppetdb bootstrap-config-file config-file config-overrides (dec bind-attempts))))))

(defn run-puppetdb [postgres-server config-overrides]
  (let [{:keys [app base-url]} (start-test-puppetdb "test-resources/integration-bootstrap.cfg"
                                                    "test-resources/integration-puppetdb.conf"
                                                    (ks/deep-merge config-overrides
                                                                   {:database (info-map postgres-server)})
                                                    10)
        {:keys [host port]} base-url]
    (PuppetDBTestServer. {:hostname host
                          :port port
                          :query-base-url (assoc base-url :prefix "/pdb/query" :version :v4)
                          :command-base-url (assoc base-url :prefix "/pdb/cmd" :version :v1)
                          :admin-base-url (assoc base-url :prefix "/pdb/admin" :version :v1)}
                         app)))

(defn pql-query [pdb-server query]
  (-> (svc-utils/create-url-str (-> pdb-server info-map :query-base-url) nil)
      (svc-utils/get-ssl {:query-params {"query" query}})
      :body))

(defn ast-query [pdb-server query]
  (pql-query pdb-server (json/generate-string query)))

;;; Puppet Server fixture

(defrecord PuppetServerTestServer [-info-map files-to-cleanup app]
  TestServer
  (info-map [_] -info-map)

  java.lang.AutoCloseable
  (close [_]
    (tk-app/stop app)
    (doseq [f files-to-cleanup] (fs/delete f))))

(def dev-config-file "./test-resources/puppetserver/puppetserver.conf")
(def dev-bootstrap-file "./test-resources/puppetserver/bootstrap.cfg")

(defn run-puppet-server [pdb-server config-overrides]
  (let [pdb-info (info-map pdb-server)
        puppetdb-conf (io/file "target/puppetserver/master-conf/puppetdb.conf")]
    (fs/copy-dir "test-resources/puppetserver/ssl" "./target/puppetserver/master-conf/ssl")
    (fs/copy+ "test-resources/puppetserver/puppet.conf" "target/puppetserver/master-conf/puppet.conf")
    (fs/mkdirs "target/puppetserver/master-code/environments/production/modules")

    (fs/create puppetdb-conf)
    (ks/spit-ini puppetdb-conf
                 {:main {:server_urls (str "https://" (:hostname pdb-info) ":" (:port pdb-info))}})

    (let [services (tk-bootstrap/parse-bootstrap-config! dev-bootstrap-file)
          tmp-conf (ks/temp-file "puppetserver" ".conf")
          _ (fs/copy dev-config-file tmp-conf)
          config (-> (tk-config/load-config (.getPath tmp-conf))
                     (ks/deep-merge config-overrides))]
      (PuppetServerTestServer. {:hostname "localhost"
                                :port 8140
                                :code-dir "target/puppetserver/master-code"
                                :conf-dir "target/puppetserver/master-conf"}
                               [(.getPath tmp-conf) "target/puppetserver/master-conf" "target/puppetserver/master-code"]
                               (tkbs/bootstrap-services-with-config services config)))))

;;; run puppet

(defn bundle-exec [& args]
  (let [result (apply sh "bundle" "exec" args)]
    (if (not (#{0 2} (:exit result)))
      (throw (ex-info (str "Error running bundle exec " (string/join " " args))
                      result))
      result)))

(defn run-puppet [puppet-server manifest-content]
  (let [{:keys [code-dir conf-dir hostname port]} (info-map puppet-server)
        site-pp (str code-dir  "/environments/production/manifests/site.pp")]
    (fs/mkdirs (fs/parent site-pp))
    (spit site-pp manifest-content)
    (bundle-exec "puppet" "agent" "-t"
                 "--confdir" conf-dir
                 "--server" hostname
                 "--masterport" (str port)
                 "--color" "false")))

;;; tests

(deftest ^:integration simple-agent-run
  (with-open [pg (setup-postgres)
              pdb (run-puppetdb pg {})
              ps (run-puppet-server pdb {})]
    (let [{:keys [out]} (run-puppet ps "notify { 'hello, world!': }")]
      (is (re-find #"hello, world" out)))
    (are [query result] (= result (pql-query pdb query))
      "nodes[certname] {}" [{:certname "localhost"}]
      "catalogs[certname, environment] {}" [{:certname "localhost", :environment "production"}]
      "factsets[certname, environment] {}" [{:certname "localhost", :environment "production"}]
      "reports[certname, environment] {}" [{:certname "localhost", :environment "production"}])))
