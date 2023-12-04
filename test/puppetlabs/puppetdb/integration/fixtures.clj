(ns puppetlabs.puppetdb.integration.fixtures
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [me.raynes.fs :as fs]
            [puppetlabs.config.typesafe :as hocon]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command :as dispatch]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.db :as dbutils]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [yaml.core :as yaml]
            [puppetlabs.puppetdb.time :as time]
            [puppetlabs.puppetdb.utils :as utils])
  (:import
   (com.typesafe.config ConfigValueFactory)
   (java.lang ProcessBuilder ProcessBuilder$Redirect)
   (java.io FileNotFoundException)))

(defprotocol TestServer
  (server-info [this]))

(defprotocol PostgresServer
  (read-db-info [this]))

;;; Postgres fixture

(defrecord PostgresTestServer [db-config read-db-config]
  TestServer
  (server-info [_] db-config)

  PostgresServer
  (read-db-info [_] read-db-config)

  java.lang.AutoCloseable
  (close [_] (dbutils/drop-test-db db-config)))

(defn setup-postgres
  ([] (setup-postgres {:migrated? true}))
  ([create-opts]
   ;; TODO: replace with configure-temp-db and ensure the read-only is properly configured/used
   (let [[db-config read-db-config] (dbutils/configure-temp-db create-opts)]
     (PostgresTestServer. db-config read-db-config))))

;;; PuppetDB fixture

(defrecord PuppetDBTestServer [info-map app]
  TestServer
  (server-info [_] info-map)

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
      {:app (tu/without-jmx
             (tkbs/parse-and-bootstrap bootstrap-config-file
                                       {:config tmp-puppetdb-conf}))
       :base-url base-url}

      (catch java.net.BindException e
        (log/errorf e "Error occured when Jetty tried to bind to port %s, attempt #%s"
                    port bind-attempts)
        (start-test-puppetdb bootstrap-config-file config-file config-overrides (dec bind-attempts))))))

(defn run-puppetdb [postgres-server config-overrides]
  (let [{:keys [app base-url]} (start-test-puppetdb "test-resources/integration-bootstrap.cfg"
                                                    "test-resources/integration-puppetdb.conf"
                                                    (ks/deep-merge config-overrides
                                                                   {:database (server-info postgres-server)
                                                                    :read-database (read-db-info postgres-server)
                                                                    :puppetdb {:disable-update-checking "true"}})
                                                    10)]
    (PuppetDBTestServer. {:base-url base-url
                          :app app}
                         app)))

(defn root-url-str [pdb-server]
  (-> pdb-server
      server-info
      :base-url
      svc-utils/root-url-str))

(defn build-url-str [pdb-server suffix]
  (-> pdb-server
      server-info
      :base-url
      (assoc :prefix suffix)
      utils/base-url->str-with-prefix))

(defn pql-query [pdb-server query]
  (-> pdb-server
      server-info
      :base-url
      (svc-utils/query-url-str nil)
      (svc-utils/get-ssl {:query-params {"query" query}})
      :body))

(defn ast-query [pdb-server query]
  (pql-query pdb-server (json/generate-string query)))

(defn call-with-synchronized-command-processing [pdb-server timeout-ms f]
  (let [result (f)]
    (if (svc-utils/wait-for-server-processing (-> pdb-server server-info :app)
                                              timeout-ms)
      result
      (throw (ex-info "" {:kind ::command-processing-timeout
                          :timeout-ms timeout-ms})))))

(defmacro with-synchronized-command-processing
  [pdb-server timeout-ms & body]
  `(call-with-synchronized-command-processing ~pdb-server ~timeout-ms
                                              (fn [] (do ~@body))))

;;; Puppet Server fixture

(defrecord PuppetServerTestServer [info-map files-to-cleanup process]
  TestServer
  (server-info [_] info-map)

  java.lang.AutoCloseable
  (close [_]
    (doto process .destroy .waitFor) ;; Assumes destroy sends a SIGTERM
    (doseq [f files-to-cleanup] (fs/delete f))))

(defn pup-srv-version []
  (try
    (string/trim (slurp "./ext/test-conf/puppetserver-dep"))
    (catch FileNotFoundException e
      (log/error "No ext/test-conf/puppetserver-dep file found. Did you run ext/bin/config-puppetserver-test-ref ?")
      (throw e))))

(def dev-config-file "./test-resources/puppetserver/puppetserver.conf")

(defn mri-agent-dir []
  (-> (sh "bundle" "show" "puppet") :out clojure.string/trim))

(defn jruby-agent-dir []
  (->> (fs/list-dir "vendor/puppetserver-gems/gems/")
       (map (fn [f] (let [[_ package version-str] (re-matches #"^(.*)-([\d\.]+)" (fs/base-name f))]
                      {:file f
                       :package-name package
                       :version (->> (string/split version-str #"\.")
                                     (map #(Integer/parseInt %))
                                     (into []))})))
       (filter #(= (:package-name %) "puppet"))
       (sort-by :version) ;; newest comes last
       (map :file)
       last))

(defn puppet-server-config-with-name [node-name]
  {:main {:certname "localhost"}
   :agent {:server "localhost"}
   :master {:storeconfigs true
            :storeconfigs_backend "puppetdb"
            :reports "puppetdb"
            :autosign true
            :node_name_value node-name}})

(defn write-puppetdb-terminus-config [pdb-servers path overrides]
  (let [f (io/file path)]
    (fs/create f)
    (tu/spit-ini f
                 (ks/deep-merge
                  {:main {:server_urls (->> pdb-servers
                                            (map (comp svc-utils/root-url-str :base-url server-info))
                                            (clojure.string/join ","))}}
                  (or overrides {})))))

(defn- wait-for-server
  [port timeout-ms]
  (loop [n (/ timeout-ms 100)]
    (when (neg? n)
      (throw (ex-info (str "server not ready within " timeout-ms "ms") {})))
    (let [res (try
                (svc-utils/get-ssl (str "https://localhost:" port "/status/v1/services"))
                (catch java.net.ConnectException _
                  ::nope))]
      (cond
        (= res ::nope) (do (Thread/sleep 100) (recur (dec n)))
        (= 200 (:status res)) true
        :else (throw (ex-info "Unexpected result from status endpoint while waiting"
                              res))))))

(defn run-puppet-server-as [node-name pdb-servers config-overrides]
  (let [puppetdb-conf (io/file "target/puppetserver/master-conf/puppetdb.conf")
        puppet-conf (io/file "target/puppetserver/master-conf/puppet.conf")
        {puppetserver-config-overrides :puppetserver
         terminus-config-overrides :terminus} config-overrides
        env-dir "target/puppetserver/master-code/environments/production"]
    (fs/copy-dir "test-resources/puppetserver/ssl" "./target/puppetserver/master-conf/ssl")
    (-> puppet-conf .getParentFile .mkdirs)
    (spit (.getAbsolutePath puppet-conf) "")
    (tu/spit-ini puppet-conf (puppet-server-config-with-name node-name))
    (fs/mkdirs (str env-dir "/modules"))

    (when tu/test-rich-data?
      (fs/copy "test-resources/puppetserver/rich_data_environment.conf" (str env-dir "/environment.conf")))

    ;; copy our custom puppet functions into the code-dir, since puppet can't
    ;; find them in the ruby load path
    (let [functions-dir (str env-dir "/lib/puppet/functions")]
      (fs/mkdirs functions-dir)
      (fs/copy-dir-into "puppet/lib/puppet/functions/" functions-dir))

    (write-puppetdb-terminus-config pdb-servers puppetdb-conf terminus-config-overrides)

    (let [port (svc-utils/open-port-num)
          config (-> (tk-config/load-config dev-config-file)
                     (merge puppetserver-config-overrides)
                     (assoc-in [:webserver :ssl-port] port))
          config-file (ks/temp-file "puppetserver-conf" ".edn")
          dev-bootstrap-file (if (string/starts-with? (pup-srv-version) "7.")
                               "./test-resources/puppetserver/bootstrap-7.x.cfg"
                               "./test-resources/puppetserver/bootstrap.cfg")
          cmd ["java" "-cp" "puppetserver/target/puppet-server-release.jar"
               "clojure.main" "-m" "puppetlabs.trapperkeeper.main"
               "services"
               "--bootstrap-config" dev-bootstrap-file
               "-c" (.getPath config-file)]
          adjust-env #(doto ^java.util.Map (.environment %)
                        (.remove "CLASSPATH"))
          pb (doto (ProcessBuilder. cmd)
               (.redirectOutput ProcessBuilder$Redirect/INHERIT)
               (.redirectError ProcessBuilder$Redirect/INHERIT)
               adjust-env)
          _ (spit config-file (pr-str config))
          process (.start pb)]
      (.addShutdownHook (Runtime/getRuntime)
                        (doto (Thread. #(.destroy process))
                          (.setName (str "Subprocess cleanup for " process))))
      (log/info (str "Started puppetserver on port " port))
      (wait-for-server port tu/default-timeout-ms)
      (PuppetServerTestServer. {:hostname "localhost"
                                :port port
                                :code-dir "target/puppetserver/master-code"
                                :conf-dir "target/puppetserver/master-conf"}
                               [(.getPath config-file)
                                "target/puppetserver/master-conf"
                                "target/puppetserver/master-code"]
                               process))))

(defn run-puppet-server [pdb-servers config-overrides]
  (run-puppet-server-as "localhost" pdb-servers config-overrides))

;;; run puppet

(defn bundle-exec [env & args]
  (let [result (apply sh "bundle" "exec"
                      (concat (map str args)
                              [:env (merge (into {} (System/getenv))
                                           env)]))]
    (if (not (#{0 2} (:exit result)))
      (let [message (str "Error running bundle exec " (string/join " " args))]
        (utils/println-err message result)
        (throw (ex-info message {:kind ::bundle-exec-failure
                                 :args args
                                 :result result})))
      result)))

(defn run-puppet
  ([puppet-server pdb-server manifest-content]
   (run-puppet puppet-server pdb-server manifest-content {}))

  ([puppet-server pdb-server manifest-content
    {:keys [certname timeout extra-puppet-args env]
     :or {certname "default-agent"
          timeout tu/default-timeout-ms
          extra-puppet-args []
          env {}}}]
   (let [{:keys [code-dir hostname port]} (server-info puppet-server)
         site-pp (str code-dir  "/environments/production/manifests/site.pp")
         agent-conf-dir (str "target/agent-conf/" certname)]
     (fs/mkdirs (fs/parent site-pp))
     (spit site-pp manifest-content)

     (fs/copy+ "test-resources/puppetserver/ssl/certs/ca.pem" (str agent-conf-dir "/ssl/certs/ca.pem"))
     (when tu/test-rich-data?
       (fs/copy "test-resources/puppetserver/rich_data_puppet.conf" (str agent-conf-dir "/puppet.conf")))

     (with-synchronized-command-processing pdb-server timeout
       (apply bundle-exec env
              "puppet" "agent" "-t"
              "--confdir" agent-conf-dir
              "--server" hostname
              "--masterport" (str port)
              "--color" "false"
              "--certname" certname
              "--trace"
              extra-puppet-args)))))

(defn run-puppet-as [certname puppet-server pdb-server manifest-content & [opts]]
  (run-puppet puppet-server pdb-server manifest-content
              (assoc opts :certname certname)))

(defn run-puppet-node-deactivate [pdb-server certname-to-deactivate]
  (with-synchronized-command-processing pdb-server tu/default-timeout-ms
    (bundle-exec {}
                 "puppet" "node" "deactivate"
                 "--confdir" "target/puppetserver/master-conf"
                 "--color" "false"
                 "--vardir" "puppet"
                 certname-to-deactivate)))

(defn run-puppet-node-status [_pdb-server certname]
  (bundle-exec {}
               "puppet" "node" "status" certname
               "--confdir" "target/puppetserver/master-conf"
               "--color" "false"
               "--vardir" "puppet"))

(defn run-puppet-facts-find [puppet-server certname]
  (let [{:keys [conf-dir]} (server-info puppet-server)]
    (-> (bundle-exec {}
                     "puppet" "facts" "find" certname
                     "--confdir" conf-dir
                     "--terminus" "puppetdb"
                     "--vardir" "puppet")
        :out
        json/parse-string)))

(defn run-puppet-apply
  "Run puppet apply configured for masterless mode, pointing at puppetdb"
  [pdb-server manifest-content
   {:keys [puppet-conf routes-yaml env extra-puppet-args terminus timeout]
    :or {puppet-conf {}
         routes-yaml {}
         env {}
         extra-puppet-args []
         terminus {}
         timeout tu/default-timeout-ms}}]

  (let [agent-conf-dir (io/file "target/puppet-apply-conf")
        manifest-file  (fs/temp-file "manifest" ".pp")
        puppet-conf-file (io/file (str agent-conf-dir "/puppet.conf"))]

    (spit manifest-file manifest-content)

    (fs/mkdir agent-conf-dir)
    (fs/create puppet-conf-file)
    (tu/spit-ini puppet-conf-file
                 (-> {:main {:ssldir "./test-resources/puppetserver/ssl"
                             :certname "localhost"
                             :storeconfigs true
                             :storeconfigs_backend "puppetdb"
                             :report true
                             :reports "puppetdb"}}
                     (ks/deep-merge puppet-conf)))

    (spit (str agent-conf-dir "/routes.yaml")
          (-> {:apply {:catalog {:terminus "compiler"
                                 :cache "puppetdb"}
                       :resource {:terminus "ral"
                                  :cache "puppetdb"}
                       :facts {:terminus "facter"
                               :cache "puppetdb_apply"}}}
              (ks/deep-merge routes-yaml)
              yaml/generate-string))

    (write-puppetdb-terminus-config [pdb-server] (str agent-conf-dir "/puppetdb.conf") terminus)

    (with-synchronized-command-processing pdb-server timeout
      (apply bundle-exec env
             "puppet" "apply" (.getCanonicalPath manifest-file)
             "--confdir" (.getCanonicalPath agent-conf-dir)
             "--vardir" "puppet"
             extra-puppet-args))))

(def date-formatter (time/formatters :date-time))

(defn query-timestamp-str [timestamp-obj]
  (time/unparse date-formatter timestamp-obj))
