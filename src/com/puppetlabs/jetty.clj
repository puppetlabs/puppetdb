;; ## Monkey patches for Ring's Jetty adapter
;;
(ns com.puppetlabs.jetty
  (:import (org.mortbay.jetty Server)
           (org.mortbay.jetty.bio SocketConnector)
           (org.mortbay.jetty.security SslSocketConnector))
  (:require [ring.adapter.jetty :as jetty])
  (:use [clojure.tools.logging :as log]))

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
    (let [blacklist (filter #(instance? sun.security.pkcs11.SunPKCS11 %) (java.security.Security/getProviders))]
      (doseq [provider blacklist]
        (log/info (str "Removing buggy security provider " provider))
        (java.security.Security/removeProvider (.getName provider))))
    (catch Throwable e
      (log/error e "Could not remove security providers; HTTPS may not work!"))))

(defn add-ssl-connector!
  "Add an SslSocketConnector to a Jetty Server instance."
  [^Server server options]
  (let [ssl-connector (SslSocketConnector.)]
    (doto ssl-connector
      (.setPort        (options :ssl-port 443))
      (.setHost        (options :ssl-host "localhost"))
      (.setKeystore    (options :keystore))
      (.setKeyPassword (options :key-password)))
    (when (options :truststore)
      (.setTruststore ssl-connector (options :truststore)))
    (when (options :trust-password)
      (.setTrustPassword ssl-connector (options :trust-password)))
    (when (options :need-client-auth)
      (.setNeedClientAuth ssl-connector true))
    (when (options :want-client-auth)
      (.setWantClientAuth ssl-connector true))
    (.addConnector server ssl-connector)))

(defn add-connector!
  "Add a plain SocketConnector to a Jetty Server instance."
  [^Server server options]
  (let [connector (SocketConnector.)]
    (doto connector
      (.setPort (options :port))
      (.setHost (options :host "localhost")))
    (.addConnector server connector)))

;; Monkey-patched version of `create-server` that will only create a
;; non-SSL connector if the options specifically dictate it.

(defn- create-server
  "Construct a Jetty Server instance."
  [options]
  (let [server (doto (Server.)
                 (.setSendDateHeader true))]
    (when (options :port)
      (add-connector! server options))
    (when (or (options :ssl?) (options :ssl-port))
      (add-ssl-connector! server options))
    server))

(defn run-jetty
  "Version of `ring.adapter.jetty/run-jetty` that uses the above
  monkey patch."
  [handler options]
  (when (empty? (select-keys options [:port :ssl? :ssl-port]))
    (throw (IllegalArgumentException. "No ports were specified to bind")))
  (with-redefs [jetty/add-ssl-connector! add-ssl-connector!
                jetty/create-server      create-server]
    (jetty/run-jetty handler options)))
