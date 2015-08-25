(ns puppetlabs.puppetdb.query.environments-test
  (:require [puppetlabs.puppetdb.query-eng :as eng]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage :as storage]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]))

(fixt/defixture super-fixture :each fixt/with-test-db)

(defn query-environments
  [version & [query]]
  (eng/stream-query-result :environments version query {} fixt/*db* ""))

(deftest test-all-environments
  (testing "without environments"
    (is (empty? (query-environments :v4))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (is (= #{{:name "foo"}
             {:name "bar"}
             {:name "baz"}}
           (set (query-environments :v4))))))

(def jsonify (comp json/parse-strict-string json/generate-string))

(deftest test-environment-queries
  (testing "without environments"
    (is (empty? (query-environments :v4))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (are [query result] (= result (set (query-environments :v4 (jsonify query))))

         '[= name foo]
         #{{:name "foo"}}

         '["~" name f.*]
         #{{:name "foo"}}

         '[not [= name foo]]
         #{{:name "bar"}
           {:name "baz"}}

         '[not ["~" name f.*]]
         #{{:name "bar"}
           {:name "baz"}}

         '[or
           [= name foo]
           [= name bar]]
         #{{:name "foo"}
           {:name "bar"}}

         '[and
           ["~" name f.*]
           ["~" name .*o]]
         #{{:name "foo"}}

         '[and
           ["~" name f.*]
           ["~" name .*o]]
         #{{:name "foo"}}))

  (testing "environment-exists? function"
    (doseq [env ["bobby" "dave" "charlie"]]
      (storage/ensure-environment env))

    (is (= true (eng/object-exists? :environment "bobby")))
    (is (= true (eng/object-exists? :environment "dave")))
    (is (= true (eng/object-exists? :environment "charlie")))
    (is (= false (eng/object-exists? :environment "ussr")))))

(deftest test-failed-comparison
  (are [query] (thrown-with-msg? IllegalArgumentException
                                 #"Query operators >,>=,<,<= are not allowed on field name"
                                 (query-environments :v4 (jsonify query)))

       '[<= name foo]
       '[>= name foo]
       '[< name foo]
       '[> name foo]))
