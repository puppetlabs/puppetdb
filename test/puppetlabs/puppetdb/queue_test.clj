(ns puppetlabs.puppetdb.queue-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.queue :refer :all]
            [clj-time.core :as t :refer [days ago now seconds]]
            [clj-time.coerce :as tcoerce]
            [clj-time.core :as time]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]
            [clojure.core.async :as async]))

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

(deftest test-sorted-command-buffer
  (testing "newer catalogs/facts cause older catalogs to be deleted"
    (let [buff (sorted-command-buffer 4)
          c (async/chan buff)
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace facts"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-4 (map->CommandRef {:id 4
                                      :command "replace facts"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (is (= 0 (count buff)))

      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2
        foo-cmd-3
        foo-cmd-4)

      (is (not (async/offer! c "should not be added")))

      (is (= (assoc foo-cmd-1 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-2
             (async/<!! c)))
      (is (= (assoc foo-cmd-3 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-4
             (async/<!! c)))))

  (testing "a new catalog after the previous one was processed"
    (let [buff (sorted-command-buffer 1)
          c (async/chan buff)
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (is (= 0 (count buff)))

      (is (async/offer! c foo-cmd-1))
      (is (= foo-cmd-1 (async/<!! c)))

      (is (async/offer! c foo-cmd-2))
      (is (= foo-cmd-2 (async/<!! c)))))

  (testing "multiple older catalogs all get marged as deleted"
    (let [c (async/chan (sorted-command-buffer 3))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-3 (map->CommandRef {:id 3
                                      :command "replace catalog"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2
        foo-cmd-3)

      (is (= (assoc foo-cmd-1 :delete? true)
             (async/<!! c)))
      (is (= (assoc foo-cmd-2 :delete? true)
             (async/<!! c)))
      (is (= foo-cmd-3
             (async/<!! c)))))

  (testing "multiple reports should remain unchanged"
    (let [c (async/chan (sorted-command-buffer 10))
          foo-cmd-1 (map->CommandRef {:id 1
                                      :command "store report"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})
          foo-cmd-2 (map->CommandRef {:id 2
                                      :command "store report"
                                      :certname "foo.com"
                                      :received (tcoerce/to-string (time/now))})]
      (are [cmd] (async/offer! c cmd)
        foo-cmd-1
        foo-cmd-2)

      (is (= foo-cmd-1 (async/<!! c)))
      (is (= foo-cmd-2 (async/<!! c))))))
