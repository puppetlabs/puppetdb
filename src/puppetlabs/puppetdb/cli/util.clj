(ns puppetlabs.puppetdb.cli.util
  "As this namespace is required by both the tk and non-tk subcommands,
  it must remain very lightweight, so that subcommands like
  \"version\" aren't slowed down by loading the entire logging
  subsystem or trapperkeeper, etc."
  (:require
   [puppetlabs.i18n.core :refer [trs]]))

(def err-exit-status 2)

;; Testing hook
(defn java-version [] (System/getProperty "java.version"))

(defn jdk-support-status [version]
  "Returns :official, :tested, or :unknown, or :no."
  (cond
    (re-matches #"1\.[123456]($|(\..*))" version) :no
    (re-matches #"1\.7($|(\..*))" version) :unknown
    (re-matches #"1\.8($|(\..*))" version) :official
    (re-matches #"10($|(\..*))" version) :tested
    :else :unknown))

(defn jdk-unsupported-msg [version]
  (let [status (jdk-support-status version)]
    (case status
      (:official :tested :unknown) nil
      (trs "PuppetDB doesn''t support JDK {0}" version))))

(defn run-cli-cmd [f]
  (let [jdk (java-version)]
    (if-let [msg (jdk-unsupported-msg jdk)]
      (do
        (binding [*out* *err*] (println (trs "error:") msg))
        err-exit-status)
      (f))))

(defn exit [status]
  (shutdown-agents)
  (binding [*out* *err*] (flush))
  (flush)
  (System/exit status))
