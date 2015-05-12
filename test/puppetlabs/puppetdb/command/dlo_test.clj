(ns puppetlabs.puppetdb.command.dlo-test
  (:require [fs.core :as fs]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.test :refer :all]
            [clj-time.core :refer [years days seconds ago now]]
            [puppetlabs.puppetdb.command.dlo :refer :all]))

(deftest dlo-compression-introspection
  (testing "an empty directory"
    (let [dir (fs/temp-dir)
          threshold (years 20)]
      (testing "should have no archives"
        (is (empty? (archives dir))))

      (testing "should have no messages"
        (is (empty? (messages dir))))

      (testing "should have no compressible files"
        (is (empty? (compressible-files dir threshold))))

      (testing "should not have a last-archived time"
        (is (nil? (last-archived dir))))

      (testing "should not already be archived"
        (is (not (already-archived? dir threshold))))))

  (testing "a directory with a few new messages"
    (let [dir (fs/temp-dir)
          threshold (-> 20 years)]
      (fs/touch (fs/file dir "foo"))
      (fs/touch (fs/file dir "bar"))
      (fs/touch (fs/file dir "baz"))

      (testing "should have no archives"
        (is (empty? (archives dir))))

      (testing "should have three messages"
        (is (= 3 (count (messages dir)))))

      (testing "should have no compressible files"
        (is (empty? (compressible-files dir threshold))))))

  (testing "a directory with some old and new messages"
    (let [dir (fs/temp-dir)
          threshold (-> 7 days)
          stale-timestamp (.getMillis (-> 8 days ago))]
      (fs/touch (fs/file dir "foo") stale-timestamp)
      (fs/touch (fs/file dir "bar") stale-timestamp)
      (fs/touch (fs/file dir "baz"))

      (testing "should have no archives"
        (is (empty? (archives dir))))

      (testing "should have three messages"
        (is (= 3 (count (messages dir)))))

      (testing "should have two compressible files"
        (is (= 2 (count (compressible-files dir threshold)))))))

  (testing "a directory with an old archive"
    (let [dir (fs/temp-dir)
          threshold (days 7)
          more-than-threshold (days 8)
          archive-time (ago more-than-threshold)]
      (fs/touch (fs/file dir (str (kitchensink/timestamp archive-time) ".tgz")))

      (testing "should have no messages"
        (is (empty? (messages dir))))

      (testing "should have an archive"
        (is (= 1 (count (archives dir)))))

      (testing "should have the right last-archived time"
        (is (= archive-time (last-archived dir))))

      (testing "should not already be archived"
        (is (not (already-archived? dir threshold))))

      (testing "and a new archive"
        (let [new-archive-time (now)]
          (fs/touch (fs/file dir (str (kitchensink/timestamp new-archive-time) ".tgz")))

          (testing "should have no messages"
            (is (empty? (messages dir))))

          (testing "should have two archives"
            (is (= 2 (count (archives dir)))))

          (testing "should have the right last-archived time"
            (is (= new-archive-time (last-archived dir))))

          (testing "should already be archived"
            (is (already-archived? dir threshold))))))))

(deftest dlo-compression
  (let [dlo (fs/temp-dir)
        threshold (-> 7 days)
        short-threshold (-> 0 seconds)
        stale-timestamp (.getMillis (-> 8 days ago))]
    (testing "should work with no subdirectories"
      (compress! "non-existent-dir" (days 7))
      (is (empty? (fs/list-dir dlo))))

    (testing "with subdirectories"
      (let [subdir (fs/temp-dir dlo)
            other-subdir (fs/temp-dir dlo)
            compression (get-in @metrics [:global :compression])
            failures (get-in @metrics [:global :compression-failures])]
        (testing "should not archive empty subdirectories"
          (compress! dlo threshold)
          (is (empty? (archives subdir)))
          (is (empty? (archives other-subdir)))

          (is (= 0 (.count compression)))
          (is (= 0 (.count failures))))

        (testing "should not archive new messages"
          (fs/touch (fs/file subdir "foo"))
          (fs/touch (fs/file other-subdir "bar"))
          (compress! dlo threshold)
          (is (= 1 (count (messages subdir))))
          (is (= 1 (count (messages other-subdir))))
          (is (empty? (archives subdir)))
          (is (empty? (archives other-subdir)))

          (is (= 0 (.count compression)))
          (is (= 0 (.count failures))))

        (testing "should archive old messages in subdirectories which haven't been archived"
          (fs/touch (fs/file subdir "foo") stale-timestamp)
          (fs/touch (fs/file other-subdir "bar") stale-timestamp)
          (compress! dlo threshold)
          (is (empty? (messages subdir)))
          (is (empty? (messages other-subdir)))
          (is (= 1 (count (archives subdir))))
          (is (= 1 (count (archives other-subdir))))

          (is (= 1 (.count compression)))
          (is (= 0 (.count failures))))

        (testing "should not archive subdirectories which have already been, even if there are old messages"
          (fs/touch (fs/file subdir "foo2") stale-timestamp)
          (fs/touch (fs/file other-subdir "bar2") stale-timestamp)
          (compress! dlo threshold)
          (is (= 1 (count (messages subdir))))
          (is (= 1 (count (messages other-subdir))))
          (is (= 1 (count (archives subdir))))
          (is (= 1 (count (archives other-subdir))))

          (is (= 1 (.count compression)))
          (is (= 0 (.count failures))))

        (testing "should archive subdirectories again after the threshold has passed"
          (compress! dlo short-threshold)
          (is (empty? (messages subdir)))
          (is (empty? (messages other-subdir)))
          (is (= 2 (count (archives other-subdir))))
          (is (= 2 (count (archives other-subdir))))

          (is (= 2 (.count compression)))
          (is (= 0 (.count failures))))))))
