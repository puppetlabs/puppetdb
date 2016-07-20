(ns puppetlabs.puppetdb.queue-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.queue :refer :all]
            [clj-time.core :as t :refer [days ago now seconds]]))

(deftest test-metadata
  (let [before-test (now)
        ;; Sleep for 1 ms to make sure we get a different receive time
        _ (Thread/sleep 1)
        s (metadata-str "replace catalog" 4 "foo.com")
        meta-map (entry->cmd s)]
    (is (= {:command "replace catalog"
            :version 4
            :certname "foo.com"}
           (dissoc meta-map :receive-time)))
    (is (t/before? before-test (:receive-time meta-map)))))
