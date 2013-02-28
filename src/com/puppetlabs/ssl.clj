(ns com.puppetlabs.ssl
  (:import (java.security Key KeyPair PrivateKey PublicKey KeyStore Security)
           (java.security.cert X509Certificate)
           (org.bouncycastle.openssl PEMReader PEMWriter)
           (org.bouncycastle.jce.provider BouncyCastleProvider))
  (:require [ring.adapter.jetty :as jetty])
  (:use [clojure.tools.logging :as log]
        [clojure.java.io :only (reader writer)]))

;; Need to make sure that the provider is initialized
(Security/addProvider (BouncyCastleProvider.))

(defn keystore
  "Create an empty in-memory Java KeyStore object."
  []
  (doto (KeyStore/getInstance "JKS")
    (.load nil)))

(defn pem->obj
  "Given a file path (or any other type supported by clojure's `reader`), reads
  a PEM-encoded object and returns an instance of the corresponding type from
  `java.security`."
  [pem]
  (let [result (-> (reader pem)
                   (PEMReader.)
                   (.readObject))]
    (log/debug (format "Loaded PEM object of type '%s' from '%s'" (class result) pem))
    result))

(defn obj->pem!
  "Encodes an object in PEM format, and writes it to a file (or other stream).  Arguments:

  `obj`: the object to encode and write.  Must be of a type that can be encoded
         to PEM; usually this is limited to certain types from the `java.security`
         packages.

  `pem`:   the file path to write the PEM output to.  (Alternately, you may pass a `Writer`
         or any other type that is supported by clojure's `writer`.)"
  [obj pem]
  (with-open [w (writer pem)]
    (doto (PEMWriter. w)
          (.writeObject obj)
          (.flush))))

(defn pem->cert
  "Given the path to a PEM file (or some other object supported by clojure's `reader`),
  decodes the contents into an instance of `X509Certificate`."
  [pem]
  {:post [(instance? X509Certificate %)]}
  (pem->obj pem))

(defn pem->private-key
  "Given the path to a PEM file (or some other object supported by clojure's `reader`),
  decodes the contents into an instance of `PrivateKey`."
  [pem]
  {:post [(instance? PrivateKey %)]}
  ; bouncy-castle's PEMReader reads a private key pem file as a KeyPair, with
  ; an empty public key.  That seems useless so we're just returning the PrivateKey.
  (.getPrivate (pem->obj pem)))

(defn pem->public-key
  "Given the path to a PEM file (or some other object supported by clojure's `reader`),
  decodes the contents into an instance of `PublicKey`."
  [pem]
  {:post [(instance? PublicKey %)]}
  (pem->obj pem))

(defn key->pem!
  "Encodes a public or private key to PEM format, and writes it to a file (or other
  stream).  Arguments:

  `key`: the key to encode and write.  Usually an instance of `PrivateKey` or `PublicKey`.
  `f`:   the file path to write the PEM output to.  (Alternately, you may pass a `Writer`
         or any other type that is supported by clojure's `writer`.)"
  [key pem]
  {:pre  [(instance? Key key)]}
  (obj->pem! key pem))

(defn assoc-cert!
  "Add a certificate to a keystore.  Arguments:

  `keystore`: the `KeyStore` to add the certificate to
  `alias`:    a String alias to associate with the certificate
  `cert`:     an `X509Certificate` to add to the keystore"
  [keystore alias cert]
  {:pre  [(instance? KeyStore keystore)
          (string? alias)
          (instance? X509Certificate cert)]
   :post [(instance? KeyStore %)]}
  (.setCertificateEntry keystore alias cert)
  keystore)

(defn assoc-cert-file!
  "Add a certificate from a PEM file to a keystore.  Arguments:

  `keystore`: the `KeyStore` to add the certificate to
  `alias`:    a String alias to associate with the certificate
  `pem-cert`: the path to a PEM file containing the certificate"
  [keystore alias pem-cert]
  (assoc-cert! keystore alias (pem->cert pem-cert)))

(defn assoc-private-key!
  "Add a private key to a keystore.  Arguments:

  `keystore`:    the `KeyStore` to add the private key to
  `alias`:       a String alias to associate with the private key
  `private-key`: the `PrivateKey` to add to the keystore
  `pw`:          a password to use to protect the key in the keystore
  `cert`:        the `X509Certificate` for the private key; a private key
                 cannot be added to a keystore without a signed certificate."
  [keystore alias private-key pw cert]
  {:pre  [(instance? KeyStore keystore)
          (string? alias)
          (instance? PrivateKey private-key)
          (or (nil? cert)
              (instance? X509Certificate cert))]
   :post [(instance? KeyStore %)]}
  (.setKeyEntry keystore alias private-key (char-array pw) (into-array [cert]))
  keystore)

(defn assoc-private-key-file!
  "Add a private key to a keystore.  Arguments:

  `keystore`:        the `KeyStore` to add the private key to
  `alias`:           a String alias to associate with the private key
  `pem-private-key`: the path to a PEM file containing the private key to add to
                     the keystore
  `pw`:              a password to use to protect the key in the keystore
  `pem-cert`:        the path to a PEM file containing the certificate for the
                     private key; a private key cannot be added to a keystore
                     without a signed certificate."
  [keystore alias pem-private-key pw pem-cert]
  (let [key  (pem->private-key pem-private-key)
        cert (pem->cert pem-cert)]
    (assoc-private-key! keystore alias key pw cert)))
