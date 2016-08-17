(ns puppetlabs.puppetdb.queue-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.queue :refer :all]
            [clj-time.core :as t :refer [days ago now seconds]]
            [clj-time.coerce :as tcoerce]
            [clj-time.core :as time]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]))

(deftest test-metadata
  (tqueue/with-stockpile q
    (let [now (time/now)
          ;; Sleep to ensure the command has a different time
          _ (Thread/sleep 1)
          cmdref (store-command q "my command" 1 "foo.com" (-> "{\"message\": \"payload\"}"
                                                               (.getBytes "UTF-8")
                                                               java.io.ByteArrayInputStream.))
           command (cmdref->cmd q cmdref)]
      (is (= {:command "my command"
              :version 1
              :certname "foo.com"
              :payload {:message "payload"}}
             (select-keys command [:command :version :certname :payload])))
      (is (t/before? now (tcoerce/from-string (get-in command [:annotations :received])))))))
