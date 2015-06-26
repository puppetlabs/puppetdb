(ns puppetlabs.pe-puppetdb-extensions.sync.services-test
  (:require [puppetlabs.pe-puppetdb-extensions.sync.services :refer :all]
            [clojure.test :refer :all]
            [slingshot.test :refer :all]))

(deftest enable-periodic-sync?-test
  (testing "Happy case"
    (is (= true (enable-periodic-sync?
                 [{:endpoint "http://foo.bar:8080", :interval 12}]))))

  (testing "Disable sync cases"
    (are [remote-config] (= false (enable-periodic-sync? remote-config))
      [{:endpoint "http://foo.bar:8080", :interval 0}]
      [{:endpoint "http://foo.bar:8080"}]
      []
      nil))

  (testing "Invalid sync configs"
    (are [remote-config] (thrown+? [:type :puppetlabs.puppetdb.utils/cli-error]
                                   (enable-periodic-sync? remote-config))

      [{:endpoint "http://foo.bar:8080", :interval "NOT A NUMBER!"}]

      [{:endpoint "http://foo.bar:8080", :interval nil}]

      [{:endpoint "http://foo.bar:8080", :interval -12}]

      [{:endpoint "http://foo.bar:8080", :interval 42}
       {:endpoint "http://baz.zap:8080", :interval 42}])))
