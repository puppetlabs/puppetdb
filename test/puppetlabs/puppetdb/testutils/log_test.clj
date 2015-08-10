(ns puppetlabs.puppetdb.testutils.log-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils :refer [temp-file]]
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

(def find-logger #'puppetlabs.puppetdb.testutils.log/find-logger)

(defn get-appenders [logger]
  (iterator-seq (.iteratorForAppenders logger)))

(deftest with-additional-log-appenders
  (let [log (atom [])
        logger (find-logger org.slf4j.Logger/ROOT_LOGGER_NAME)
        uuid (kitchensink/uuid)
        original-appenders (get-appenders logger)
        new-appender (#'puppetlabs.puppetdb.testutils.log/log-event-listener
                      (fn [event] (swap! log conj event)))]
    (tgt/with-additional-log-appenders org.slf4j.Logger/ROOT_LOGGER_NAME
      [new-appender]
      (is (= (set (cons new-appender original-appenders))
             (set (get-appenders logger))))
      (log/error uuid))
    (is (= (set original-appenders)
           (set (get-appenders logger))))
    (is (some #(= {:level "ERROR" :message uuid} %)
              (map event->map @log)))))

(deftest with-log-appenders
  (let [log (atom [])
        logger (find-logger org.slf4j.Logger/ROOT_LOGGER_NAME)
        uuid (kitchensink/uuid)
        original-appenders (get-appenders logger)
        new-appender (#'puppetlabs.puppetdb.testutils.log/log-event-listener
                      (fn [event] (swap! log conj event)))]
    (tgt/with-log-appenders org.slf4j.Logger/ROOT_LOGGER_NAME
      [new-appender]
      (is (= [new-appender] (get-appenders logger)))
      (log/error uuid))
    (is (= (set original-appenders)
           (set (get-appenders logger))))
    (is (some #(= {:level "ERROR" :message uuid} %)
              (map event->map @log)))))

(deftest with-log-listener
  (let [log (atom [])
        uuid (kitchensink/uuid)]
    (tgt/with-log-level Logger/ROOT_LOGGER_NAME :info
      (tgt/with-log-event-listener Logger/ROOT_LOGGER_NAME
        (fn [event] (swap! log conj event))
        (log/info uuid))
      (is (is (some #(= {:level "INFO" :message uuid} %)
                    (map event->map @log)))))))

(deftest suppressing-log-unless-error
  (tgt/with-log-suppressed-unless-notable tgt/notable-pdb-event?
   (log/info "shouldn't see this"))
  (let [uuid (kitchensink/uuid)
        expected (format "shouldn't see this %s" uuid)]
    (is (not (re-matches (re-pattern (str expected ".*"))
                         (with-out-str
                           (binding [*err* *out*]
                             (tgt/with-log-suppressed-unless-notable
                               tgt/notable-pdb-event?
                               (log/info expected))))))))
  (let [uuid (kitchensink/uuid)
        expected (format "not really an error, but should be shown %s" uuid)]
    (is (re-find (re-pattern expected)
                 (with-out-str
                   (binding [*err* *out*]
                     (tgt/with-log-suppressed-unless-notable
                       tgt/notable-pdb-event?
                       (log/error expected))))))))
