(ns puppetlabs.puppetdb.testutils.queue
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [me.raynes.fs :refer [delete-dir]]
            [puppetlabs.stockpile.queue :as stock]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.testutils.nio :refer [create-temp-dir]]
            [puppetlabs.puppetdb.queue :as q]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as ks]
            [clj-time.core :refer [now]]))

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

(defn catalog->command-req [version {:keys [certname name] :as catalog}]
  (q/create-command-req "replace catalog"
                        version
                        (or certname name)
                        (ks/timestamp (now))
                        ""
                        identity
                        (coerce-to-stream catalog)))

(defn facts->command-req [version {:keys [certname name] :as facts}]
  (q/create-command-req "replace facts"
                        version
                        (or certname name)
                        (ks/timestamp (now))
                        ""
                        identity
                        (coerce-to-stream facts)))

(defn deactivate->command-req [version {:keys [certname] :as command}]
  (q/create-command-req "deactivate node"
                        version
                        (case version
                          3 certname
                          2 (json/parse-string command)
                          1 (json/parse-string (json/parse-string command)))
                        (ks/timestamp (now))
                        ""
                        identity
                        (coerce-to-stream command)))

(defn report->command-req [version {:keys [certname name] :as command}]
  (q/create-command-req "store report"
                        version
                        (or certname name)
                        (ks/timestamp (now))
                        ""
                        identity
                        (coerce-to-stream command)))
