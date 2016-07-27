(ns puppetlabs.puppetdb.testutils.queue
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [stockpile :as stock]
            [puppetlabs.puppetdb.testutils.nio :as nio]))

(defn rm-r [pathstr]
  ;; Life's too short...
  (assert (zero? (:exit (shell/sh "rm" "-r" pathstr)))))

(defmacro with-stockpile-dir [sym & body]
  `(let [ns-str#  (str (ns-name ~*ns*))
         ~sym (-> (nio/path-get "target" ns-str#)
                  (nio/create-temp-dir "stk")
                  (.resolve "q")
                  str)]
     (try
       ~@body
       (finally rm-r ~sym))))


(defmacro with-stockpile [queue-sym & body]
  `(let [ns-str#  (str (ns-name ~*ns*))
         queue-dir# (-> (nio/path-get "target" ns-str#)
                        (nio/create-temp-dir "stk")
                        (.resolve "q")
                        str)
         ~queue-sym (stock/create queue-dir#)]
     (try
       ~@body
       (finally rm-r queue-dir#))))
