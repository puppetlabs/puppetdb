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

(deftest with-additional-log-appenders
  ;; Definitely incomplete
  (let [log (atom [])
        uuid (kitchensink/uuid)]
    (tgt/with-additional-log-appenders org.slf4j.Logger/ROOT_LOGGER_NAME
      [(#'puppetlabs.puppetdb.testutils.log/log-event-listener
        (fn [event] (swap! log conj event)))]
      (log/error uuid))
    (is (some #(= {:level "ERROR" :message uuid} %)
              (map event->map @log)))))

(deftest with-log-appenders
  ;; Definitely incomplete
  (let [log (atom [])
        uuid (kitchensink/uuid)]
    (tgt/with-additional-log-appenders org.slf4j.Logger/ROOT_LOGGER_NAME
      [(#'puppetlabs.puppetdb.testutils.log/log-event-listener
        (fn [event] (swap! log conj event)))]
      (log/error uuid))
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
  (tgt/suppressing-log-unless-error
   (log/info "shouldn't see this"))
  (let [uuid (kitchensink/uuid)
        expected (format "shouldn't see this %s" uuid)]
    (is (not (re-matches (re-pattern (str expected ".*"))
                         (with-out-str
                           (binding [*err* *out*]
                             (tgt/suppressing-log-unless-error
                              (log/info expected))))))))
  (let [uuid (kitchensink/uuid)
        expected (format "not really an error, but should be shown %s" uuid)]
    (is (re-find (re-pattern expected)
                 (with-out-str
                   (binding [*err* *out*]
                     (tgt/suppressing-log-unless-error
                      (log/error expected))))))))
