;; ## CLI invokation
;;
;; This is a tiny shim that delegates the command-line arguments to an
;; appropriate handler.
;;
;; If the user executes the program with arguments like so:
;;
;;     ./this-program foo arg1 arg2 arg3
;;
;; ...then we'll look for a namespace called
;; `com.puppetlabs.cmdb.cli.foo` and invoke its `-main` method with
;; `[arg1 arg2 arg3]`.

(ns com.puppetlabs.cmdb.core
  (:gen-class))

(defn -main
  [& args]
  (let [module (str "com.puppetlabs.cmdb.cli." (first args))
        args (rest args)]
    (require (symbol module))
    (apply (resolve (symbol module "-main")) args)))
