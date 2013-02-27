(ns com.puppetlabs.test.ssl
  (:import java.util.Arrays)
  (:use clojure.test
        com.puppetlabs.ssl
        [clojure.java.io :only [resource reader]]))

(deftest testssl
  (let [private-key-file (resource "com/puppetlabs/test/ssl/private_keys/localhost.pem")
        cert-file        (resource "com/puppetlabs/test/ssl/certs/localhost.pem")]
    (testing "assoc-private-key-file!"
      (let [keystore            (keystore)
            _                   (assoc-private-key-file! keystore "mykey" private-key-file "bunkpassword" cert-file)
            keystore-key        (.getKey keystore "mykey" (char-array "bunkpassword"))
            private-key         (pem->private-key private-key-file)]
        (testing "key read from keystore should match key read from pem"
          (is (Arrays/equals (.getEncoded private-key) (.getEncoded keystore-key))))
        (testing "pem created from keystore should match original pem file"
          (let [pem-writer-stream   (java.io.ByteArrayOutputStream.)
                _                   (key->pem! keystore-key pem-writer-stream)]
            (is (Arrays/equals (-> (reader private-key-file)
                                   (slurp)
                                   (.getBytes))
                               (.toByteArray pem-writer-stream)))))))))
