(ns puppetlabs.puppetdb.testutils.log-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.testutils.log :as tgt])
  (:import [org.slf4j Logger LoggerFactory]))

(defn- event->map [event]
  {:level (str (.getLevel event))
   :message (.getMessage event)})

(deftest with-log-level-and-logging-to-atom
  (let [log (atom [])]
    (tgt/with-log-level Logger/ROOT_LOGGER_NAME :error
      (tgt/with-logging-to-atom Logger/ROOT_LOGGER_NAME log
        (log/info "wlta-test"))
      (is (not-any? #(= {:level "INFO" :message "wlta-test"} %)
                    (map event->map @log)))))
  (let [log (atom [])]
    (tgt/with-log-level Logger/ROOT_LOGGER_NAME :info
      (tgt/with-logging-to-atom Logger/ROOT_LOGGER_NAME log
        (log/info "wlta-test"))
      (is (some #(= {:level "INFO" :message "wlta-test"} %)
                (map event->map @log))))))
