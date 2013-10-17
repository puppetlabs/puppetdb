(ns com.puppetlabs.test.ssl
  (:import java.util.Arrays
           [java.security PrivateKey])
  (:use clojure.test
        com.puppetlabs.ssl
        [clojure.java.io :only [resource reader]]))

(deftest privkeys
  (testing "assoc-private-key-file!"
    (let [private-key-file (resource "com/puppetlabs/test/ssl/private_keys/localhost.pem")
          cert-file        (resource "com/puppetlabs/test/ssl/certs/localhost.pem")
          keystore         (keystore)
          _                (assoc-private-key-file! keystore "mykey" private-key-file "bunkpassword" cert-file)
          keystore-key     (.getKey keystore "mykey" (char-array "bunkpassword"))
          private-key      (pem->private-key private-key-file)]

      (testing "key read from keystore should match key read from pem"
        (is (Arrays/equals (.getEncoded private-key) (.getEncoded keystore-key))))

      (testing "pem created from keystore should match original pem file"
        (let [pem-writer-stream   (java.io.ByteArrayOutputStream.)
              _                   (key->pem! keystore-key pem-writer-stream)]
          (is (Arrays/equals (-> (reader private-key-file)
                                 (slurp)
                                 (.getBytes))
                             (.toByteArray pem-writer-stream))))))))

(deftest rsakeyonly
  (testing "reading PEM files with only the RSA-key should work"
    (let [privkey (resource "com/puppetlabs/test/ssl/private_keys/keyonly.pem")]
      (is (instance? PrivateKey (pem->private-key privkey))))))
