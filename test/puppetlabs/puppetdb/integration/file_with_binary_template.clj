(ns puppetlabs.puppetdb.integration.file-with-binary-template
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [me.raynes.fs :as fs]))

(deftest ^:integration file-with-binary-template
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]

    (let [temp-dir (fs/temp-dir "file-with-binary-template")
          file-resource-path (str temp-dir "/binary-file")
          binary-template-path (fs/absolute "test-resources/binary-template.erb")]

      (testing "Run puppet with a binary template"
        (int/run-puppet-as "binary-file-agent" ps pdb
                           (str "file { '" file-resource-path "':"
                                "  content => template('" binary-template-path "'),"
                                "  tag => 'binary_file',"
                                "}")))

      (testing "PDB should have stored the resource"
        (is (= {:certname "binary-file-agent"
                :type "File"
                :title file-resource-path}
               (first (int/pql-query pdb "resources [certname, type, title] { tag = 'binary_file' }"))))))))
