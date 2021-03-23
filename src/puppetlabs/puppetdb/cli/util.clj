(ns puppetlabs.puppetdb.cli.util
  "As this namespace is required by both the tk and non-tk subcommands,
  it must remain very lightweight, so that subcommands like
  \"version\" aren't slowed down by loading the entire logging
  subsystem or trapperkeeper, etc."
  (:require
   [puppetlabs.i18n.core :refer [trs]]))

(def err-exit-status 2)

;; FIXME: maybe change this to rely on java.lang.Runtime$Version for
;; jdk > 8 (cf. pdb-jdk-ver in project.clj).

;; Testing hook
(defn java-version [] (System/getProperty "java.version"))

(def supported-java-version "11")

(defn jdk-support-status
  "Returns :official, :tested, :deprecated, :unknown, or :no."
  [version]
  (cond
    (re-matches #"1\.[1234567]($|(\..*))" version) :no
    (re-matches #"1\.[89]($|(\..*))" version) :deprecated
    (re-matches #"10($|(\..*))" version) :deprecated
    (re-matches (re-pattern (str supported-java-version "($|(\\..*))")) version) :official
    :else :unknown))

(defn jdk-unsupported-msg [version]
  (let [status (jdk-support-status version)]
    (case status
      (:unknown) {:warn (trs "JDK {0} is neither tested nor supported. Please use JDK {1}" version supported-java-version)}
      (:deprecated) {:warn (trs "JDK {0} is deprecated, please upgrade to JDK {1}" version supported-java-version)}
      (:official :tested) nil
      {:error (trs "PuppetDB doesn''t support JDK {0}" version)})))

(defn run-cli-cmd [f]
  (let [jdk (java-version)]
    (if-let [{:keys [warn error]} (jdk-unsupported-msg jdk)]
      (do
        (if error
          (do
            (binding [*out* *err*] (println (trs "error:") error))
            err-exit-status)
          (do
            (binding [*out* *err*] (println (trs "warn:") warn))
            (f))))
      (f))))

(defn exit [status]
  (shutdown-agents)
  (binding [*out* *err*] (flush))
  (flush)
  (System/exit status))
