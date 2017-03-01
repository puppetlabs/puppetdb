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
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.trapperkeeper.core :as tk]
            [yaml.core :as yaml]
            [slingshot.slingshot :refer [throw+]])
  (:import [com.typesafe.config ConfigValueFactory]))

(defprotocol TestServer
  (info-map [this]))

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
                                                                   {:database (info-map postgres-server)})
                                                    10)
        {:keys [host port]} base-url]
    (PuppetDBTestServer. {:hostname host
                          :port port
                          :query-base-url (assoc base-url :prefix "/pdb/query" :version :v4)
                          :command-base-url (assoc base-url :prefix "/pdb/cmd" :version :v1)
                          :admin-base-url (assoc base-url :prefix "/pdb/admin" :version :v1)
                          :app app}
                         app)))

(defn root-url-str [pdb-server]
  (let [{:keys [hostname port]} (info-map pdb-server)]
    (format "https://%s:%s" hostname port)))

(defn pql-query [pdb-server query]
  (-> (svc-utils/create-url-str (-> pdb-server info-map :query-base-url) nil)
      (svc-utils/get-ssl {:query-params {"query" query}})
      :body))

(defn entity-query [pdb-server url-suffix query & [params]]
  (-> (svc-utils/create-url-str (-> pdb-server info-map :query-base-url) url-suffix)
      (svc-utils/get-ssl {:query-params (merge {"query" (json/generate-string query)}
                                               params)})
      :body))

(defn ast-query [pdb-server query]
  (pql-query pdb-server (json/generate-string query)))

(defn call-with-synchronized-command-processing [pdb-server num-commands timeout-ms f]
  (let [dispatcher (-> pdb-server info-map :app (tk-app/get-service :PuppetDBCommandDispatcher))
        initial-count (-> dispatcher dispatch/stats :executed-commands)
        target-count (+ initial-count num-commands)
        result (f)]
    (let [timeout-start (System/currentTimeMillis)]
      (loop []
        (let [current-stats (dispatch/stats dispatcher)]
          (cond
            (= (:executed-commands current-stats) target-count)
            result

            ;; on the first run for an agent, puppetserver likes to send an extra factset
            (= (:executed-commands current-stats) (inc target-count))
            result

            (> (:executed-commands current-stats) target-count)
            (throw+ {:kind ::too-many-commands-processed
                     :current-stats current-stats
                     :initial-count initial-count
                     :target-count target-count
                     :body-result result})

            (> (- (System/currentTimeMillis) timeout-start) timeout-ms)
            (throw+ {:kind ::command-processing-timeout
                     :current-stats current-stats
                     :initial-count initial-count
                     :target-count target-count
                     :timeout-ms timeout-ms
                     :body-result result})

            :default
            (do
              (Thread/sleep 25)
              (recur))))))))

(defmacro with-synchronized-command-processing [pdb-server num-commands timeout-ms & body]
  `(call-with-synchronized-command-processing ~pdb-server ~num-commands ~timeout-ms (fn [] (do ~@body))))

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


(defn mri-agent-dir []
  (-> (sh "bundle" "show" "puppet") :out clojure.string/trim))

(defn jruby-agent-dir []
  (->> (fs/list-dir "vendor/puppetserver-gems/gems/")
       sort
       (filter #(re-matches #"puppet-\d+\.\d+\.\d+" (fs/base-name %)))
       (map fs/absolute)
       last))

(defn install-terminus-into [puppet-dir]
  ;; various pieces of the terminus have to actually be inside of puppet itself to work
  (let [base-dir "puppet"
        base-uri (.toURI (io/file base-dir))
        terminus-files (->> (fs/find-files base-dir #".*")
                            (filter #(.isFile %))
                            (map #(-> (.relativize base-uri (.toURI %))
                                      .getPath)))]
    (doseq [relative-path terminus-files]
      (let [in (str base-dir "/" relative-path)
            out (str puppet-dir "/" relative-path)]
        (when-not (fs/exists? out)
          (println "Copying puppetdb terminus file" in "to" out)
          (fs/copy+ in out))))))

(defn puppet-server-config-with-name [node-name]
  {:main {:certname "localhost"}
   :agent {:server "localhost"}
   :master {:storeconfigs true
            :storeconfigs_backend "puppetdb"
            :reports "puppetdb"
            :autosign true
            :node_name_value node-name}})

(defn write-puppetdb-terminus-config [pdb-servers path overrides]
  (let [pdb-infos (map info-map pdb-servers)
        f (io/file path)]
    (fs/create f)
    (ks/spit-ini f
                 (ks/deep-merge
                  {:main {:server_urls (->> (for [pdb-info pdb-infos]
                                              (str "https://" (:hostname pdb-info) ":" (:port pdb-info)))
                                            (clojure.string/join ","))}}
                  (or overrides {})))))

(defn run-puppet-server-as [node-name pdb-servers config-overrides]
  (let [puppetdb-conf (io/file "target/puppetserver/master-conf/puppetdb.conf")
        puppet-conf (io/file "target/puppetserver/master-conf/puppet.conf")
        {puppetserver-config-overrides :puppetserver
         terminus-config-overrides :terminus} config-overrides]
    (fs/copy-dir "test-resources/puppetserver/ssl" "./target/puppetserver/master-conf/ssl")
    (-> puppet-conf .getParentFile .mkdirs)
    (spit (.getAbsolutePath puppet-conf) "")
    (ks/spit-ini puppet-conf (puppet-server-config-with-name node-name))
    (fs/mkdirs "target/puppetserver/master-code/environments/production/modules")

    (install-terminus-into (jruby-agent-dir))
    (write-puppetdb-terminus-config pdb-servers puppetdb-conf terminus-config-overrides))

  (let [services (tk-bootstrap/parse-bootstrap-config! dev-bootstrap-file)
        tmp-conf (ks/temp-file "puppetserver" ".conf")
        _ (fs/copy dev-config-file tmp-conf)
        config (tk-config/load-config (.getPath tmp-conf))]
    (PuppetServerTestServer. {:hostname "localhost"
                              :port 8140
                              :code-dir "target/puppetserver/master-code"
                              :conf-dir "target/puppetserver/master-conf"}
                             [(.getPath tmp-conf)
                              "target/puppetserver/master-conf"
                              "target/puppetserver/master-code"]
                             (tkbs/bootstrap-services-with-config services config))))

(defn run-puppet-server [pdb-servers config-overrides]
  (run-puppet-server-as "localhost" pdb-servers config-overrides))

;;; run puppet

(defn bundle-exec [env & args]
  (let [result (apply sh "bundle" "exec"
                      (concat args [:env (merge (into {} (System/getenv))
                                                env)]))]
    (if (not (#{0 2} (:exit result)))
      (let [message (str "Error running bundle exec " (string/join " " args))]
        (println message result)
        (throw+ {:kind ::bundle-exec-failure
                 :args args
                 :result result}
                nil
                message))
      result)))

(defn run-puppet
  ([puppet-server pdb-server manifest-content]
   (run-puppet puppet-server pdb-server manifest-content {}))

  ([puppet-server pdb-server manifest-content
    {:keys [certname timeout extra-puppet-args env]
     :or {certname "default-agent"
          timeout tu/default-timeout-ms
          extra-puppet-args []
          env {}}
     :as opts}]
   (let [{:keys [code-dir conf-dir hostname port]} (info-map puppet-server)
         site-pp (str code-dir  "/environments/production/manifests/site.pp")
         agent-conf-dir (str "target/agent-conf/" certname)]
     (fs/mkdirs (fs/parent site-pp))
     (spit site-pp manifest-content)

     (fs/copy+ "test-resources/puppetserver/ssl/certs/ca.pem" (str agent-conf-dir "/ssl/certs/ca.pem"))

     (with-synchronized-command-processing pdb-server 3 timeout
       (apply bundle-exec env
              "puppet" "agent" "-t"
              "--confdir" agent-conf-dir
              "--server" hostname
              "--masterport" (str port)
              "--color" "false"
              "--certname" certname
              "--trace"
              extra-puppet-args)))))

(defn run-puppet-as [certname puppet-server pdb-server manifest-content & [extra-puppet-args]]
  (run-puppet puppet-server pdb-server manifest-content
              {:certname certname
               :extra-puppet-args extra-puppet-args}))

(defn run-puppet-node-deactivate [pdb-server certname-to-deactivate]
  (install-terminus-into (mri-agent-dir))
  (with-synchronized-command-processing pdb-server 1 tu/default-timeout-ms
    (bundle-exec {}
                 "puppet" "node" "deactivate"
                 "--confdir" "target/puppetserver/master-conf"
                 "--color" "false"
                 certname-to-deactivate)))

(defn run-puppet-node-status [pdb-server certname]
  (install-terminus-into (mri-agent-dir))
  (bundle-exec {}
               "puppet" "node" "status" certname
               "--confdir" "target/puppetserver/master-conf"
               "--color" "false"))

(defn run-puppet-facts-find [puppet-server certname]
  (let [{:keys [conf-dir]} (info-map puppet-server)]
    (-> (bundle-exec {}
                     "puppet" "facts" "find" certname
                     "--confdir" conf-dir
                     "--terminus" "puppetdb")
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
         timeout tu/default-timeout-ms}
    :as opts}]

  (install-terminus-into (mri-agent-dir))

  (let [agent-conf-dir (io/file "target/puppet-apply-conf")
        manifest-file  (fs/temp-file "manifest" ".pp")
        puppet-conf-file (io/file (str agent-conf-dir "/puppet.conf"))]

    (spit manifest-file manifest-content)

    (fs/mkdir agent-conf-dir)
    (fs/create puppet-conf-file)
    (ks/spit-ini puppet-conf-file
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

    (with-synchronized-command-processing pdb-server 3 timeout
      (apply bundle-exec env
             "puppet" "apply" (.getCanonicalPath manifest-file)
             "--confdir" (.getCanonicalPath agent-conf-dir)
             extra-puppet-args))))
