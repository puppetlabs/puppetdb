(ns puppetlabs.puppetdb.testutils.queue
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [stockpile :as stock]
            [puppetlabs.puppetdb.testutils.nio :as nio]))

(defn rm-r [pathstr]
  (let [rm (shell/sh "rm" "-r" pathstr)]
    (when-not (zero? (:exit rm))
      (throw (-> "'rm -r %s' failed: %s"
                 (format (pr-str pathstr) (pr-str rm))
                 Exception.)))))

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
