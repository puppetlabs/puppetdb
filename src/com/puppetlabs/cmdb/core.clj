(ns com.puppetlabs.cmdb.core
  (:gen-class))

(defn -main
  [& args]
  (let [module (str "com.puppetlabs.cmdb.cli." (first args))
        args (rest args)]
    (require (symbol module))
    (apply (resolve (symbol module "-main")) args)))
