(ns puppetlabs.puppetdb.cli.export-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.export :as export]
            [clj-time.core :refer [now]]
            [clojure.test :refer :all]))

(deftest export
  (testing "Export metadata"
    (let [{:keys [msg file-suffix contents]} (export/export-metadata (now))
          metadata (json/parse-string contents true)]
      (is (= {:replace_catalog 6
              :store_report 5
              :replace_facts 4}
             (:command_versions metadata)))
      (is (= ["export-metadata.json"] file-suffix)))))
