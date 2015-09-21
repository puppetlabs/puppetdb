(ns puppetlabs.puppetdb.cli.anonymize-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.anonymize :refer :all]
            [puppetlabs.puppetdb.testutils :as tu]
            [clojure.string :as str]
            [puppetlabs.puppetdb.testutils.tar :as tar]
            [puppetlabs.puppetdb.testutils.facts :refer [spit-facts-tarball create-host-facts]]))

(deftest test-anonymize-facts
  (testing "with profile none"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "bar"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "none")
          orig-data (tar/tar->map in-path)
          anon-data (tar/tar->map anon-output)]
      (is (= (get orig-data "facts")
             (get anon-data "facts")))))

  (testing "with profile low"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "foo"
                                                                      "reallysecret" "bar"
                                                                      "totallyprivate" "baz"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "low")
          orig-data (tar/tar->map in-path)
          orig-facts (get-in orig-data ["facts" "foo.com.json"])
          {host "certname" anon-env "environment" anon-facts "values"} (first (vals (get (tar/tar->map anon-output) "facts")))]

      (are [k v] (= v (get-in orig-data ["facts" "foo.com.json" "values" k]))
           "password" "foo"
           "reallysecret" "bar"
           "totallyprivate" "baz")

      (is (not (str/blank? host)))
      (is (not= host "foo.com"))
      (is (contains? anon-facts "password"))
      (is (contains? anon-facts "reallysecret"))
      (is (contains? anon-facts "totallyprivate"))

      (are [k] (not= (get orig-facts ["values" k]) (get anon-facts k))
           "password"
           "reallysecret"
           "totallyprivate")

      (is (= (get orig-facts "environment")
             anon-env))))

  (testing "with profile moderate"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "foo"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "moderate")
          orig-data (tar/tar->map in-path)
          orig-facts (get-in orig-data ["facts" "foo.com.json"])
          {host "certname" anon-env "environment" anon-facts "values"} (first (vals (get (tar/tar->map anon-output) "facts")))]

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

      (are [op k] (op (get-in orig-facts ["values" k]) (get anon-facts k))
           = "id"
           not= "ipaddress_lo0"
           = "operatingsystem")

      (is (= (count (get orig-facts "values"))
             (count anon-facts)))

      (is (not= (get orig-data "environment")
                anon-env))))


  (testing "with profile full"
    (let [in-path (.getPath (tu/temp-file "input-facts" ".tar.gz"))
          _ (spit-facts-tarball in-path (create-host-facts "foo.com" {"password" "foo"}))
          anon-output (tu/temp-file "anon-facts" ".tar.gz")
          _ (-main "-i" in-path "-o" (.getPath anon-output) "-p" "full")
          orig-data (tar/tar->map in-path)
          orig-facts (get-in orig-data ["facts" "foo.com.json"])
          {host "certname" anon-env "environment" anon-facts "values"} (first (vals (get (tar/tar->map anon-output) "facts")))]

      (are [k v] (= v (get-in orig-facts ["values" k]))
           "password" "foo"
           "id" "foo"
           "ipaddress_lo0" "127.0.0.1"
           "operatingsystem" "Debian")

      (is (not-any? anon-facts (keys (get orig-facts "values"))))
      (is (not-any? (set (vals anon-facts))
                    (vals (get orig-data "values"))))
      (is (not= (get orig-data "environment")
                anon-env)))))
