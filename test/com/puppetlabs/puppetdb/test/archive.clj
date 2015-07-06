(ns com.puppetlabs.puppetdb.test.archive
  (:import  [java.io ByteArrayOutputStream ByteArrayInputStream])
  (:use     [clojure.test])
  (:require [com.puppetlabs.archive :as archive]
            [clojure.java.io :as io]))

(deftest test-tar-read-write
  (let [bazfile     {:path   (.getPath (io/file "foo" "bar" "baz.txt"))
                     :content "This is a test"}
        blingfile   {:path   (.getPath (io/file "foo" "blah" "bling.txt"))
                     :content "This is another test"}
        tar-entries {(:path bazfile) (:content bazfile)
                     (:path blingfile) (:content blingfile)}
        out-stream (ByteArrayOutputStream.)]
    (testing "should be able to write a simple tarball w/o errors"
      (with-open [tar-writer (archive/tarball-writer out-stream)]
        (archive/add-entry tar-writer "UTF-8" (:path bazfile) (:content bazfile))
        (archive/add-entry tar-writer "UTF-8" (:path blingfile) (:content blingfile))))

    (with-open [in-stream (ByteArrayInputStream. (.toByteArray out-stream))]
      (with-open [tar-reader (archive/tarball-reader in-stream)]
        (testing "should be able to find a specific entry in a tarball"
          (let [bling-entry (archive/find-entry tar-reader (:path blingfile))]
            (is (not (nil? bling-entry)))
            (is (= (:content blingfile) (archive/read-entry-content tar-reader)))))))

    (with-open [in-stream (ByteArrayInputStream. (.toByteArray out-stream))]
      (with-open [tar-reader (archive/tarball-reader in-stream)]
        (let [entry-count (atom 0)]
          (testing "should only contain the expected entries, with expected content"
            (doseq [tar-entry (archive/all-entries tar-reader)]
              (swap! entry-count inc)
              (is (contains? tar-entries (.getName tar-entry)))
              (let [content (archive/read-entry-content tar-reader)]
                (is (= content (tar-entries (.getName tar-entry)))))))

          (testing "should contain the correct number of entries"
            (is (= (count tar-entries) @entry-count))))))))
