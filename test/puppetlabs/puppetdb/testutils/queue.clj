(ns puppetlabs.puppetdb.testutils.queue
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]))

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
