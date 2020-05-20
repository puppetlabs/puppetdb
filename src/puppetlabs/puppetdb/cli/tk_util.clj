(ns puppetlabs.puppetdb.cli.tk-util
  "This namespace is separate from cli.util because we don't want to
  require any more than we have to there."
  (:require
   [clojure.tools.logging :as log]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.puppetdb.cli.util :as cliu]))

(defn run-tk-cli-cmd [f]
  (let [jdk (cliu/java-version)]
    (if-let [{:keys [warn error]} (cliu/jdk-unsupported-msg jdk)]
      (if error
        (do
          (binding [*out* *err*] (println (trs "error:") error))
          (log/error error)
          cliu/err-exit-status)
        (do
          (binding [*out* *err*] (println (trs "warn:") warn))
          (log/warn warn)
          (f)))
      (f))))
