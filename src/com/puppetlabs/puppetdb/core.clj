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
;; `com.puppetlabs.puppetdb.cli.foo` and invoke its `-main` method with
;; `[arg1 arg2 arg3]`.

(ns com.puppetlabs.puppetdb.core
  (:require [clojure.tools.namespace :as ns])
  (:use [clojure.string :only (split)]
        [clojure.stacktrace :only [print-stack-trace]])
  (:gen-class))

(def ns-prefix "com.puppetlabs.puppetdb.cli.")

(defn cli-namespaces
  "Return a set of namespaces underneath the .cli parent"
  []
  {:post [(set? %)]}
  (into #{} (for [namespace (ns/find-namespaces-on-classpath)
                  :let [ns-str (name namespace)]
                  :when (.startsWith ns-str ns-prefix)]
              namespace)))

(defn var-from-ns
  "Resolve a var, by name, from a (potentially un-required)
  namespace. If no matching var is found, returns nil.

  Example:

    (var-from-ns \"clojure.string\" \"split\")"
  [ns v]
  {:pre [(string? ns)
         (string? v)]}
  (require (symbol ns))
  (if-let [var (resolve (symbol ns v))]
    (var-get var)))

(defn available-subcommands
  "Return the set of available subcommands for this application"
  []
  {:post [(set? %)]}
  (into #{} (for [namespace (cli-namespaces)
                  :let [ns-str (name namespace)
                        subcmd (last (split ns-str #"\."))]]
              subcmd)))

(defn usage
  "Display help text to the user"
  []
  (let [cmds (sort (for [cmd (available-subcommands)]
                     [cmd (var-from-ns (str ns-prefix cmd) "cli-description")]))]
    (println "Available subcommands:\n")
    (doseq [[subcommand description] cmds]
      (println subcommand "\t" (or description "")))
    (println "\nFor help on a given subcommand, invoke it with -h")))

(defn -main
  [& args]
  (let [subcommand (first args)
        allowed?   (available-subcommands)]

    ;; Bad invokation
    (when-not (allowed? subcommand)
      (usage)
      (System/exit 1))

    (let [module (str ns-prefix subcommand)
          args (rest args)]
      (require (symbol module))
      (try
        (apply (resolve (symbol module "-main")) args)
        (System/exit 0)
        (catch Throwable e
          (binding [*out* *err*]
            (print-stack-trace e)
            (println)
            (System/exit 1)))))))
