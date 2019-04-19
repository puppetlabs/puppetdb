(ns puppetlabs.puppetdb.test-import
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.import :refer :all]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.time :refer [now]]))

(defn cmd-path-with-hash [entity producer-ts-hash certname]
  (format "puppetdb-bak/%s/%s-%s.json" entity certname producer-ts-hash))

(def producer-ts-hash (hash/generic-identity-hash (now)))

(deftest extract-command-with-hash
  (doseq [entity ["catalogs" "reports"]]
    (are [certname] (= (-> entity
                           (cmd-path-with-hash producer-ts-hash certname)
                           command-matcher)
                       [entity certname])
      "foo.com"
      "foo"
      "some-really-long-name-with-dashes"
      "some-name-with-double-json.json")))

(defn facts-path [certname]
  (format "puppetdb-bak/facts/%s.json" certname))

(deftest test-extract-facts-certname
  (are [certname] (= (command-matcher (facts-path certname))
                     ["facts" certname])
    "foo.com"
    "foo"
    "some-really-long-name-with-dashes"
    "some-name-with-double-json.json"))
