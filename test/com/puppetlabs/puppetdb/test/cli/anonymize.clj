(ns com.puppetlabs.puppetdb.test.cli.anonymize
  (:require [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.cli.anonymize :refer :all]
            [clojure.java.io :as io]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [com.puppetlabs.archive :as archive]
            [com.puppetlabs.cheshire :as json]
            [fs.core :as fs]
            [clojure.string :as str]
            [clj-time.core :as time]
            [com.puppetlabs.puppetdb.testutils.tar :as tar]
            [com.puppetlabs.puppetdb.testutils.facts :refer [spit-facts-tarball create-host-facts]]))

(deftest test-anonymize-facts
  (testing "with profile none"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "bar"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "none")
          orig-data (tar/mapify in-path)
          anon-data (tar/mapify anon-output)]
      (is (= (get orig-data "facts")
             (get anon-data "facts")))))

  (testing "with profile low"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "foo"
                                                                      "reallysecret" "bar"
                                                                      "totallyprivate" "baz"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "low")
          orig-data (tar/mapify in-path)
          {host "name" anon-facts "values"} (first (vals (get (tar/mapify anon-output) "facts")))]

      (are [k v] (= v (get-in orig-data ["facts" "foo.com.json" "values" k]))
           "password" "foo"
           "reallysecret" "bar"
           "totallyprivate" "baz")

      (is (not (str/blank? host)))
      (is (not= host "foo.com"))
      (is (contains? anon-facts "password"))
      (is (contains? anon-facts "reallysecret"))
      (is (contains? anon-facts "totallyprivate"))

      (is (not= (get-in orig-data ["facts" "foo.com.json" "values" "password"])
                (get anon-facts "password")))
      (is (not= (get-in orig-data ["facts" "foo.com.json" "values" "reallysecret"])
                (get anon-facts "reallysecret")))
      (is (not= (get-in orig-data ["facts" "foo.com.json" "values" "totallyprivate"])
                (get anon-facts "totallyprivate")))))

  (testing "with profile moderate"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "foo"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "moderate")
          orig-data (tar/mapify in-path)
          {host "name" anon-facts "values"} (first (vals (get (tar/mapify anon-output) "facts")))]

      (are [k v] (= v (get-in orig-data ["facts" "foo.com.json" "values" k]))
           "password" "foo"
           "id" "foo"
           "ipaddress_lo0" "127.0.0.1"
           "operatingsystem" "Debian")

      (is (not (str/blank? host)))
      (is (not= host "foo.com"))
      (is (not (contains? anon-facts "password")))
      (is (contains? anon-facts "id"))
      (is (contains? anon-facts "operatingsystem"))

      (is (= (get-in orig-data ["facts" "foo.com.json" "values" "id"])
             (get anon-facts "id")))
      (is (not= (get-in orig-data ["facts" "foo.com.json" "values" "ipaddress_lo0"])
                (get anon-facts "ipaddress_lo0")))
      (is (= (get-in orig-data ["facts" "foo.com.json" "values" "operatingsystem"])
             (get anon-facts "operatingsystem")))
      (is (= (count (get-in orig-data ["facts" "foo.com.json" "values"]))
             (count anon-facts)))))

  (testing "with profile full"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "foo"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "full")
          orig-data (tar/mapify in-path)
          {host "name" anon-facts "values"} (first (vals (get (tar/mapify anon-output) "facts")))]

      (are [k v] (= v (get-in orig-data ["facts" "foo.com.json" "values" k]))
           "password" "foo"
           "id" "foo"
           "ipaddress_lo0" "127.0.0.1"
           "operatingsystem" "Debian")

      (is (not-any? anon-facts (keys (get-in orig-data ["facts" "foo.com.json" "values"]))))
      (is (not-any? (set (vals anon-facts))
                    (vals (get-in orig-data ["facts" "foo.com.json" "values"])))))))
