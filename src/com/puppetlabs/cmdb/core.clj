(ns com.puppetlabs.cmdb.core
  (:gen-class)
  (:use [clojure.tools.cli :only (cli optional required group)]))

(defn -main
  [& args]
  (let [module (str "com.puppetlabs.cmdb.cli." (first args))
        args (rest args)]
    (require (symbol module))
    (apply (resolve (symbol module "-main")) args)))
