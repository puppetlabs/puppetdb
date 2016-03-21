(ns puppetlabs.puppetdb.query.ast-test
  "Tests the behavior of AST queries."
  (:require
   [clj-time.coerce :as coerce-time]
   [clj-time.core :refer [now]]
   [clojure.test :refer :all]
   [puppetlabs.kitchensink.core :refer [dissoc-in]]
   [puppetlabs.puppetdb.cli.services :as cli-svcs]
   [puppetlabs.puppetdb.command :refer [enqueue-command]]
   [puppetlabs.puppetdb.examples :as examples]
   [puppetlabs.puppetdb.testutils :refer [block-until-results]]
   [puppetlabs.puppetdb.testutils.cli :refer [get-catalogs get-factsets]]
   [puppetlabs.puppetdb.testutils.services :as svc-utils :refer [*server*]]
   [puppetlabs.trapperkeeper.app :refer [get-service]]))

(deftest ast-queries
  (svc-utils/with-single-quiet-pdb-instance
    (let [dispatcher (get-service *server* :PuppetDBCommandDispatcher)
          pdb (get-service *server* :PuppetDBServer)
          query (fn [ast] (cli-svcs/query pdb :v4 ast {} set))]

      (enqueue-command dispatcher :replace-facts 4
                       {:certname "foo.local"
                        :environment "dev"
                        :values {:foo 1
                                 :bar 2
                                 :baz 3
                                 :match "match"}
                        :producer_timestamp (coerce-time/to-string (now))})
      @(block-until-results 100 (first (get-factsets "foo.local")))

      (testing "that \"in\" fields can be duplicated"
        (is (= #{{:name "match" :value "match"}}
               (query ["from" "facts"
                       ["extract" ["name" "value"]
                        ["in" ["value" "value"]
                         ["extract" ["name" "value"]
                          ["select_facts"]]]]]))))
      (testing "that \"in\" field order is respected"
        (is (= #{{:name "foo" :value 1}
                 {:name "bar" :value 2}
                 {:name "baz" :value 3}
                 {:name "match" :value "match"}}
               (query ["from" "facts"
                       ["extract" ["name" "value"]
                        ["in" ["name" "value"]
                         ["extract" ["name" "value"]
                          ["select_facts"]]]]])))
        (is (= #{{:name "match" :value "match"}}
               (query ["from" "facts"
                       ["extract" ["name" "value"]
                        ["in" ["name" "value"]
                         ["extract" ["value" "name"]
                          ["select_facts"]]]]]))))
      (testing "that rx match works across types"
        (is (= #{{:name "match" :value "match"}}
               (query ["from" "facts"
                       ["extract" ["name" "value"]
                        ["~" "name" "match"]]]))))
      (testing "that \"in\" works if only the field is a multi"
        (is (= #{{:name "match" :value "match"}}
               (query ["from" "facts"
                       ["extract" ["name" "value"]
                        ["in" ["value"]
                         ["extract" ["name"]
                          ["select_facts"]]]]]))))
      (testing "that \"in\" works if only the subquery projection is a multi"
        (is (= #{{:name "match" :value "match"}}
               (query ["from" "facts"
                       ["extract" ["name" "value"]
                        ["in" ["name"]
                         ["extract" ["value"]
                          ["select_facts"]]]]]))))
      (testing "that \"in\" works if field and subquery projection are multi"
        (is (= #{{:name "foo" :value 1}
                 {:name "bar" :value 2}
                 {:name "baz" :value 3}
                 {:name "match" :value "match"}}
               (query ["from" "facts"
                       ["extract" ["name" "value"]
                        ["in" ["value"]
                         ["extract" ["value"]
                          ["select_facts"]]]]])))))))

(deftest fact-value-resource-title-join
  (svc-utils/with-single-quiet-pdb-instance
    (let [dispatcher (get-service *server* :PuppetDBCommandDispatcher)
          pdb (get-service *server* :PuppetDBServer)
          query (fn [ast] (cli-svcs/query pdb :v4 ast {} set))
          certname "foo.local"
          environment "dev"
          facts {:certname certname
                 :environment environment
                 :values {:apache_conf "/etc/apache/apache2.conf"
                          :foo 1
                          :bar 2
                          :baz 3}
                 :producer_timestamp (coerce-time/to-string (now))}
          resource {:type "File"
                    :title "/etc/apache/apache2.conf"
                    :exported false
                    :file "/tmp/foo"
                    :line 10
                    :tags ["file" "class" "blarg"]
                    :parameters {:ensure "directory"
                                 :group "root"
                                 :source "my_file_source"
                                 :user "root"}}
          resources (-> (get-in examples/wire-catalogs [8 :empty])
                        (assoc :certname certname)
                        (assoc :environment environment)
                        (update :resources conj resource))]
      (enqueue-command dispatcher :replace-facts 4 facts)
      (enqueue-command dispatcher :replace-catalog 8 resources)
      @(block-until-results 100 (first (get-factsets certname)))
      @(block-until-results 100 (first (get-catalogs certname)))

      (testing "that joins from resource title to fact value work"
        (is (= #{(-> resource
                     (dissoc :parameters) ;; json in result, and don't care
                     (update :tags set)
                     (merge {:certname certname :environment environment}))}
               (set (map #(-> %
                              (dissoc :parameters :resource)
                              (update :tags set))
                         (query ["from" "resources"
                                 ["and"
                                  ["=" "type" "File"]
                                  ["in" ["title"]
                                   ["extract" ["value"]
                                    ["select_facts"
                                     ["=" "name" "apache_conf"]]]]]])))))))))
