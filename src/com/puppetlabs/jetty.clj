;; ## Monkey patches for Ring's Jetty adapter
;;
(ns com.puppetlabs.jetty
  (:import (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.nio SelectChannelConnector))
  (:require [ring.adapter.jetty :as jetty]
            [clojure.tools.logging :as log]
            [clojure.string :refer [split trim]]
            [com.puppetlabs.utils :refer [compare-jvm-versions]]))

;; We need to monkey-patch `add-ssl-connector!` in order to set the
;; appropriate options for Client Certificate Authentication, and use
;; an ssl-specific host for the socket listener.

;; Work around an issue with OpenJDK's PKCS11 implementation preventing TLSv1
;; connections from working correctly
;;
;; http://stackoverflow.com/questions/9586162/openjdk-and-php-ssl-connection-fails
;; https://bugs.launchpad.net/ubuntu/+source/openjdk-6/+bug/948875
(if (re-find #"OpenJDK" (System/getProperty "java.vm.name"))
  (try
    (let [klass     (Class/forName "sun.security.pkcs11.SunPKCS11")
          blacklist (filter #(instance? klass %) (java.security.Security/getProviders))]
      (doseq [provider blacklist]
        (log/info (str "Removing buggy security provider " provider))
        (java.security.Security/removeProvider (.getName provider))))
    (catch ClassNotFoundException e)
    (catch Throwable e
      (log/error e "Could not remove security providers; HTTPS may not work!"))))

;; Due to weird issues between JSSE and OpenSSL clients on some 1.7
;; jdks when using Diffie-Hellman exchange, we need to only enable
;; RSA-based ciphers.
;;
;; https://forums.oracle.com/forums/thread.jspa?messageID=10999587
;; https://issues.apache.org/jira/browse/APLO-287
;;
;; If not running on an affected JVM version, this is nil.
(defn acceptable-ciphers
  ([]
     (acceptable-ciphers (System/getProperty "java.version")))
  ([jvm-version]
     (let [known-good-version "1.7.0_05"]
       (if (pos? (compare-jvm-versions jvm-version known-good-version))
         ;; We're more recent than the last known-good version, and hence
         ;; are busted
         ["TLS_RSA_WITH_AES_256_CBC_SHA256"
          "TLS_RSA_WITH_AES_256_CBC_SHA"
          "TLS_RSA_WITH_AES_128_CBC_SHA256"
          "TLS_RSA_WITH_AES_128_CBC_SHA"
          "SSL_RSA_WITH_RC4_128_SHA"
          "SSL_RSA_WITH_3DES_EDE_CBC_SHA"
          "SSL_RSA_WITH_RC4_128_MD5"]))))

;; Monkey-patched version of `create-server` that will only create a
;; non-SSL connector if the options specifically dictate it.

(defn plaintext-connector
  [options]
  (doto (SelectChannelConnector.)
    (.setPort (options :port 80))
    (.setHost (options :host "localhost"))))

(defn- create-server
  "Construct a Jetty Server instance."
  [options]
  (let [server (doto (Server.)
                 (.setSendDateHeader true))]
    (when (options :port)
      (.addConnector server (plaintext-connector options)))

    (when (or (options :ssl?) (options :ssl-port))
      (let [ssl-host  (options :ssl-host (options :host "localhost"))
            options   (assoc options :host ssl-host)
            connector (#'jetty/ssl-connector options)
            ciphers   (if-let [txt (options :cipher-suites)]
                        (map trim (split txt #","))
                        (acceptable-ciphers))
            protocols (if-let [txt (options :ssl-protocols)]
                        (map trim (split txt #",")))]
        (when ciphers
          (let [fac (.getSslContextFactory connector)]
            (.setIncludeCipherSuites fac (into-array ciphers))
            (when protocols
              (.setIncludeProtocols fac (into-array protocols)))))
        (.addConnector server connector)))
    server))

(defn run-jetty
  "Version of `ring.adapter.jetty/run-jetty` that uses the above
  monkey patch."
  [handler options]
  (when (empty? (select-keys options [:port :ssl? :ssl-port]))
    (throw (IllegalArgumentException. "No ports were specified to bind")))
  (with-redefs [jetty/create-server create-server]
    (jetty/run-jetty handler options)))
