(ns puppetlabs.pe-puppetdb-extensions.rbac-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.rbac.services.authn :refer [rbac-authn-service]]
            [puppetlabs.rbac.services.authz :refer [rbac-authz-service]]
            [puppetlabs.rbac.services.http.api :refer [rbac-http-api-service]]
            [puppetlabs.rbac.services.http.middleware :refer [rbac-authn-middleware]]
            [puppetlabs.rbac.services.rbac :refer [rbac-service]]
            [puppetlabs.rbac.services.status :refer [rbac-status-service]]
            [puppetlabs.rbac.services.storage.permissioned :refer [rbac-storage-service]]
            [puppetlabs.rbac.services.consumer :refer [rbac-consumer-service]]
            [puppetlabs.activity.services :refer [activity-reporting-service activity-service]]
            [puppetlabs.rbac.testutils.services.dev-login :refer [dev-login-service]]
            [puppetlabs.rbac-client.testutils.dummy-rbac-service :refer [dummy-rbac-service]]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.testutils.db :as tudb :refer [*db* with-test-db]]
            [puppetlabs.pe-puppetdb-extensions.testutils :refer [pe-services]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.trapperkeeper.app :refer [get-service stop]]))

(def ^:dynamic *rbac-db* nil)
(def ^:dynamic *activity-db* nil)

(defn puppetdb-and-rbac-config
  []
  (let [rbac-ssl-port (svcs/open-port-num)]
    (-> (svcs/create-temp-config)
        (dissoc :jetty)
        (assoc-in [:puppetdb :certificate-whitelist] "./test-resources/cert-whitelist.txt")
        (assoc-in [:global :certs] {:ssl-ca-cert "./test-resources/ca.pem"
                                    :ssl-cert "./test-resources/localhost.pem"
                                    :ssl-key "./test-resources/localhost.key"})
        (assoc :database *db*
               :rbac-consumer {:api-url (format "https://localhost:%s/rbac-api" rbac-ssl-port)}
               :rbac-ldap {:url "ldap://localhost:10389"
                           :user-dn-template "uid={0},ou=users,o=myDn"}
               :rbac-embedded-dev-ldap {:host "localhost"
                                        :port 10389}
               :rbac-static {:ds-trust-store-cache (tu/temp-dir)}
               :rbac {:password-reset-expiration 24
                      :session-timeout 60
                      :failed-attempts-lockout 10
                      :certificate-whitelist "./test-resources/cert-whitelist.txt"
                      :token-private-key "./test-resources/localhost.key"
                      :token-public-key "./test-resources/localhost.pem"
                      :token-signing-algorithm "RS512"
                      :database (assoc *rbac-db* :maximum-pool-size 5)}
               :activity {:database (assoc *activity-db* :maximum-pool-size 5)}
               :webserver
               {:rbac {:host "0.0.0.0"
                       :port (svcs/open-port-num)
                       :ssl-host "0.0.0.0"
                       :ssl-port rbac-ssl-port
                       :ssl-ca-cert "./test-resources/ca.pem"
                       :ssl-cert "./test-resources/localhost.pem"
                       :ssl-key "./test-resources/localhost.key"
                       :client-auth "want"}
                :default {:host "0.0.0.0"
                          :port (svcs/open-port-num)
                          :ssl-host "0.0.0.0"
                          :ssl-port (svcs/open-port-num)
                          :ssl-ca-cert "./test-resources/ca.pem"
                          :ssl-cert "./test-resources/localhost.pem"
                          :ssl-key "./test-resources/localhost.key"
                          :client-auth "want"}}
               :web-router-service
               {:puppetlabs.pe-puppetdb-extensions.sync.pe-routing/pe-routing-service {:route "/pdb"
                                                                                       :server "default"}
                :puppetlabs.rbac.services.http.api/rbac-http-api-service {:route "/rbac-api"
                                                                          :server "rbac"}
                :puppetlabs.rbac.testutils.services.dev-login/dev-login-service {:route "/auth"
                                                                                 :server "rbac"}
                :puppetlabs.activity.services/activity-service {:route "/activity-api"
                                                                :server "rbac"}}))))

(def ^:dynamic *server* nil)
(def ^:dynamic *pdb-url* nil)
(def ^:dynamic *pdb-ssl-url* nil)
(def ^:dynamic *rbac-ssl-url* nil)
(def ^:dynamic *pdb-status-ssl-url* nil)

(defn call-with-puppetdb-and-rbac-instance
  ([f]
   (call-with-puppetdb-and-rbac-instance 10 f))
  ([bind-attempts f]
   (when (zero? bind-attempts)
     (throw (RuntimeException. "Repeated attempts to bind port failed, giving up")))
   (let [config (puppetdb-and-rbac-config)
         services (distinct (concat (remove #{#'dummy-rbac-service} pe-services)
                                    [#'activity-reporting-service
                                     #'activity-service
                                     #'rbac-service
                                     #'rbac-status-service
                                     #'rbac-storage-service
                                     #'rbac-authn-service
                                     #'rbac-authz-service
                                     #'rbac-authn-middleware
                                     #'rbac-http-api-service
                                     #'rbac-consumer-service
                                     #'dev-login-service]))]
     (try
       (tkbs/with-app-with-config server
         (map var-get services)
         (conf/adjust-and-validate-tk-config config)
         (binding [*server* server
                   *pdb-url* (format "http://localhost:%d/pdb" (get-in config [:webserver :default :port]))
                   *pdb-ssl-url* (format "https://localhost:%d/pdb" (get-in config [:webserver :default :ssl-port]))
                   *pdb-status-ssl-url* (format "https://localhost:%d/status/v1/services" (get-in config [:webserver :default :ssl-port]))
                   *rbac-ssl-url* (format "https://localhost:%d/rbac-api" (get-in config [:webserver :rbac :ssl-port]))]
           (f)))
       (catch java.net.BindException e
         (log/errorf e "Error occured when Jetty tried to bind ports for PuppetDB and RBAC, attempt #%s"
                     bind-attempts)
         (call-with-puppetdb-and-rbac-instance (dec bind-attempts) f))))))

(defn create-temp-db [name]
  (let [db-name (if-not (var-get #'tudb/pdb-test-id)
                  (format "pdb_test_%s" name)
                  (format "pdb_test_%s_%s" (var-get #'tudb/pdb-test-id) name))]
    (log/info db-name)
    (jdbc/with-db-connection (tudb/db-admin-config)
      (jdbc/do-commands-outside-txn
       (format "drop database if exists %s" db-name)
       (format "create database %s" db-name)))
    (jdbc/with-db-connection (tudb/db-admin-config db-name)
      (jdbc/do-commands-outside-txn
       "create extension if not exists citext"))
    (#'tudb/db-user-config db-name)))

(defn login-request-map []
  {:as :text
   :headers {"content-type" "application/json"}
   :body (json/generate-string {"email" ""
                                "login" "puppetdb"
                                "password" "puppetdb"
                                "role_ids" [1]
                                "display_name" "PuppetDB Administrator"})
   :ssl-cert "./test-resources/localhost.pem"
   :ssl-key "./test-resources/localhost.key"
   :ssl-ca-cert "./test-resources/ca.pem"})

(deftest rbac-integration
  (binding [*rbac-db* (create-temp-db "rbac")]
    (try
      (binding [*activity-db* (create-temp-db "activity")]
        (try
          (with-test-db
            (call-with-puppetdb-and-rbac-instance
             (fn []
               (http-client/post (str *rbac-ssl-url* "/v1/users")
                                 (login-request-map))

               (let [response (http-client/post (str *rbac-ssl-url* "/v1/auth/token")
                                                {:as :text
                                                 :headers {"content-type" "application/json"}
                                                 :body (json/generate-string {"lifetime" "60m"
                                                                              "login" "puppetdb"
                                                                              "password" "puppetdb"})
                                                 :ssl-ca-cert "./test-resources/ca.pem"})
                     token (-> (:body response)
                               (json/parse-string true)
                               :token)]

                 (is (= 200 (:status (http-client/get (str *pdb-ssl-url* "/meta/v1/version")
                                                      {:as :text
                                                       :headers {"x-authentication" token}
                                                       :ssl-ca-cert "./test-resources/ca.pem"}))))
                 (let [response (-> (http-client/get *pdb-status-ssl-url*
                                                     {:as :text
                                                      :headers {"x-authentication" token}
                                                      :ssl-ca-cert "./test-resources/ca.pem"})
                                    :body
                                    (json/parse-string true))]

                   (is (= "running" (get-in response [:puppetdb-status :state])))
                   (is (= "running" (get-in response [:puppetdb-status :status :rbac_status])))))
               

               (is (= 200 (:status (http-client/get (str *pdb-url* "/meta/v1/version")
                                                    {:as :text}))))
               (is (= 200 (:status (http-client/get (str *pdb-ssl-url* "/meta/v1/version")
                                                    {:as :text
                                                     :ssl-cert "./test-resources/localhost.pem"
                                                     :ssl-key "./test-resources/localhost.key"
                                                     :ssl-ca-cert "./test-resources/ca.pem"}))))

               (is (= 403 (:status (http-client/get (str *pdb-ssl-url* "/meta/v1/version")
                                                    {:as :text
                                                     :ssl-ca-cert "./test-resources/ca.pem"}))))

               (is (= 401 (:status (http-client/get (str *pdb-ssl-url* "/meta/v1/version")
                                                    {:as :text
                                                     :headers {"x-authentication" "bad token"}
                                                     :ssl-ca-cert "./test-resources/ca.pem"})))))))
          (finally (#'tudb/drop-test-db *activity-db*))))
      (finally (#'tudb/drop-test-db *rbac-db*)))))

(deftest check-rbac-down
  (binding [*rbac-db* (create-temp-db "rbac")]
    (try
      (binding [*activity-db* (create-temp-db "activity")]
        (try
          (with-test-db
            (call-with-puppetdb-and-rbac-instance
             (fn []
               (http-client/post (str *rbac-ssl-url* "/v1/users")
                                 (login-request-map))

               (let [response (http-client/post (str *rbac-ssl-url* "/v1/auth/token")
                                                {:as :text
                                                 :headers {"content-type" "application/json"}
                                                 :body (json/generate-string {"lifetime" "60m"
                                                                              "login" "puppetdb"
                                                                              "password" "puppetdb"})
                                                 :ssl-ca-cert "./test-resources/ca.pem"})
                     token (-> (:body response)
                               (json/parse-string true)
                               :token)]

                 (#'tudb/drop-test-db *rbac-db*)

                 (let [response (-> (http-client/get *pdb-status-ssl-url*
                                                     {:as :text
                                                      :headers {"x-authentication" token}
                                                      :ssl-ca-cert "./test-resources/ca.pem"})
                                    :body
                                    (json/parse-string true))]
                   (is (= "unknown" (get-in response [:puppetdb-status :state])))
                   (is (= "error" (get-in response [:puppetdb-status :status :rbac_status]))))))))
          (finally (#'tudb/drop-test-db *activity-db*))))
      (finally
        (jdbc/with-db-connection (tudb/db-admin-config)
          ;;Only if the test fails before the db has been dropped do we need to drop it here
          (when (seq (jdbc/query-to-vec "SELECT 1 from pg_database WHERE datname='rbac'"))
            (#'tudb/drop-test-db *rbac-db*)))))))

