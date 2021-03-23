(ns puppetlabs.puppetdb.dev.lein
  (:require
   [clojure.edn :as edn]
   [puppetlabs.puppetdb.lint :refer [ignore-value]])
  (:import
   (java.lang ProcessBuilder
              ProcessBuilder$Redirect)))

(defn msgn [stream & items]
  (binding [*out* stream]
    (apply println items)))

(defn die [& items]
  (apply msgn *err* items)
  (binding [*out* *err*] (flush))
  (System/exit 2))

(defn run-sh [edn-args & other-args]
  (let [args (edn/read-string edn-args)
        _ (when-not (seq args)
            (die "run-sh: no arguments provided"))
        [opts cmd] (if (= 1 (count args))
                     (let [arg (first args)]
                       (when-not (string? arg)
                         (die "run-sh: argument not a string" (pr-str arg)))
                       [nil args])
                     (let [maybe-opts (first args)]
                       (if (map? maybe-opts)
                         [maybe-opts (rest args)]
                         [nil args])))
        {:keys [check? echo argc] :or {check? true}} opts
        cmd (concat cmd other-args)]
    (doseq [x cmd]
      (when-not (string? x)
        (die "run-sh: non-string argument in" (pr-str cmd))))
    (doseq [k (keys opts)]
      (when-not (#{:check? :echo :argc} k)
        (die "run-sh: invalid option" k)))
    (when echo
      (when-not (#{nil true :edn} echo)
        (die "run-sh: invalid :echo option value" (pr-str echo))))
    (when argc
      (when-not (set? argc)
        (die "run-sh: :argc must be an integer set, not" (pr-str argc)))
      (when-not (argc (count other-args))
        (die "run-sh: command line argument count" (count other-args)
             "is not in" argc)))
    (when echo
      (binding [*out* *err*]
        (ignore-value (apply (case echo true print :edn pr) cmd))
        (newline)
        (flush)))
    (let [proc (doto (ProcessBuilder. cmd)
                 (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                 (.redirectError ProcessBuilder$Redirect/INHERIT))
          rc (-> proc .start .waitFor)]
      (when (and check? (not (zero? rc)))
        (System/exit rc)))))
