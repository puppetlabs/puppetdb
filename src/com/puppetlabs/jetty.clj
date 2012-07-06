;; ## Monkey patches for Ring's Jetty adapter
;;
(ns com.puppetlabs.jetty
  (:import (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.nio SelectChannelConnector))
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
    (let [klass     (Class/forName "sun.security.pkcs11.SunPKCS11")
          blacklist (filter #(instance? klass %) (java.security.Security/getProviders))]
      (doseq [provider blacklist]
        (log/info (str "Removing buggy security provider " provider))
        (java.security.Security/removeProvider (.getName provider))))
    (catch ClassNotFoundException e)
    (catch Throwable e
      (log/error e "Could not remove security providers; HTTPS may not work!"))))

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
      (let [ssl-host (options :ssl-host (options :host "localhost"))
            options  (assoc options :host ssl-host)]
        (.addConnector server (#'jetty/ssl-connector options))))
    server))

(defn run-jetty
  "Version of `ring.adapter.jetty/run-jetty` that uses the above
  monkey patch."
  [handler options]
  (when (empty? (select-keys options [:port :ssl? :ssl-port]))
    (throw (IllegalArgumentException. "No ports were specified to bind")))
  (with-redefs [jetty/create-server create-server]
    (jetty/run-jetty handler options)))
