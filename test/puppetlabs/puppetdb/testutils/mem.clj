(ns puppetlabs.puppetdb.testutils.mem
  (:require [clojure.java.shell :as shell])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files NoSuchFileException]))

(def getpid
  "Returns the integer PID of the current process."
  (let [pid (atom nil)]
    (fn []
      (if-let [p @pid]
        p
        (reset! pid (->> (ManagementFactory/getRuntimeMXBean)
                         .getName
                         (re-find #"[0-9]+")
                         Integer/parseInt))))))

(defn dump-heap
  "Dumps the current heap to filename via jmap."
  [filename]
  (try
    ;; Because jmap quietly does nothing if the file exists.
    (-> filename java.io.File. .toPath Files/delete)
    (catch NoSuchFileException ex
      true))
  (assert (zero?
           (:exit (shell/sh "jmap"
                            (str "-dump:live,format=b,file=" filename)
                            (str (getpid)))))))
