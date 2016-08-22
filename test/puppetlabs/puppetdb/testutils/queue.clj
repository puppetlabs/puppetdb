(ns puppetlabs.puppetdb.testutils.queue
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [stockpile :as stock]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.testutils.nio :refer [create-temp-dir]]
            [puppetlabs.puppetdb.queue :as q]
            [puppetlabs.puppetdb.cheshire :as json]))

(defn rm-r [pathstr]
  (let [rm (shell/sh "rm" "-r" pathstr)]
    (when-not (zero? (:exit rm))
      (throw (-> "'rm -r %s' failed: %s"
                 (format (pr-str pathstr) (pr-str rm))
                 Exception.)))))

(defmacro with-stockpile [queue-sym & body]
  `(let [ns-str#  (str (ns-name ~*ns*))
         queue-dir# (-> (get-path "target" ns-str#)
                        (create-temp-dir "stk")
                        (.resolve "q")
                        str)
         ~queue-sym (stock/create queue-dir#)]
     (try
       ~@body
       (finally (rm-r queue-dir#)))))

(defprotocol CoerceToStream
  (coerce-to-stream [x]
    "Converts the given input to the input stream that stockpile requires"))

(extend-protocol CoerceToStream
  java.io.InputStream
  (coerce-to-stream [x] x)
  String
  (coerce-to-stream [x]
    (java.io.ByteArrayInputStream. (.getBytes x "UTF-8")))
  clojure.lang.IEditableCollection
  (coerce-to-stream [x]
    (let [baos (java.io.ByteArrayOutputStream.)]
      (with-open [osw (java.io.OutputStreamWriter. baos java.nio.charset.StandardCharsets/UTF_8)]
        (json/generate-stream x osw))
      (.close baos)
      (-> baos
          .toByteArray
          java.io.ByteArrayInputStream.))))

(defn store-command [q command-type version certname payload]
  (q/store-command q command-type version certname (coerce-to-stream payload)))
