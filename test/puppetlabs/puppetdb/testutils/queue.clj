(ns puppetlabs.puppetdb.testutils.queue
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [me.raynes.fs :refer [delete-dir]]
            [puppetlabs.stockpile.queue :as stock]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.testutils.nio :refer [create-temp-dir]]
            [puppetlabs.puppetdb.queue :as q]
            [puppetlabs.puppetdb.cheshire :as json]))

(defmacro with-stockpile [queue-sym & body]
  `(let [ns-str#  (str (ns-name ~*ns*))
         queue-dir# (-> (get-path "target" ns-str#)
                        (create-temp-dir "stk")
                        (.resolve "q")
                        str)
         ~queue-sym (stock/create queue-dir#)]
     (try
       ~@body
       (finally
         (delete-dir queue-dir#)))))

(defprotocol CoerceToStream
  (-coerce-to-stream [x]
    "Converts the given input to the input stream that stockpile requires"))

(extend-protocol CoerceToStream
  java.io.InputStream
  (-coerce-to-stream [x] x)
  String
  (-coerce-to-stream [x]
    (java.io.ByteArrayInputStream. (.getBytes x "UTF-8")))
  clojure.lang.IEditableCollection
  (-coerce-to-stream [x]
    (let [baos (java.io.ByteArrayOutputStream.)]
      (with-open [osw (java.io.OutputStreamWriter. baos java.nio.charset.StandardCharsets/UTF_8)]
        (json/generate-stream x osw))
      (.close baos)
      (-> baos
          .toByteArray
          java.io.ByteArrayInputStream.))))

(defn coerce-to-stream [x]
  (-coerce-to-stream x))

(defn store-command
  ([q command-type version certname payload]
   (store-command q command-type version certname nil payload))
  ([q command-type version certname producer-ts payload]
   (q/store-command q command-type version certname producer-ts (coerce-to-stream payload))))
