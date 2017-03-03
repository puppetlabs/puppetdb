(ns puppetlabs.puppetdb.integration.reports.report-storage
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.cheshire :as json]))

(defn get-href [pdb suffix]
  (-> pdb
      int/root-url-str
      (str suffix)
      svc-utils/get-ssl
      :body))

(deftest ^:integration metrics-and-logs-storage
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (testing "Initial agent run, to populate puppetdb with data to query"
      (int/run-puppet-as "my_agent" ps pdb
                         (str "notify { 'hi':"
                              "  message => 'Hi my_agent' "
                              "}")
                         ["--noop"])

      ;; This is a bit weird as well; all "skipped" resources during a puppet
      ;; run will end up having events generated for them.  However, during a
      ;; typical puppet run there are a bunch of "Schedule" resources that will
      ;; always show up as skipped.  Here we filter them out because they're
      ;; not really interesting for this test.
      (let [result (int/pql-query pdb "reports { certname = 'my_agent' }")
            [event :as events] (remove #(= (:resource_type %) "Schedule")
                                       (int/pql-query pdb (format "events { report = '%s' }"
                                                                  (-> result first :hash))))]
        (are [x y] (= x y)
          1 (count events)
          true (:noop (first result))
          "Notify" (:resource_type event)
          "hi" (:resource_title event)
          "message" (:property event)
          "Hi my_agent" (:new_value event))))

    (testing "agent run without noop"
      (int/run-puppet-as "my_agent" ps pdb
                         (str "notify { 'hi':"
                              "  message => 'Hi my_agent' "
                              "}"))

      (let [[report] (int/pql-query pdb "reports { certname = 'my_agent' }")
            metrics (get-href pdb (get-in report [:metrics :href]))
            logs  (get-href pdb (get-in report [:logs :href]))]

        (is (= #{{:name "total", :value 1, :category "events"}
                 {:name "changed", :value 1, :category "resources"}
                 {:name "total", :value 1, :category "changes"}
                 {:name "total", :value 8, :category "resources"}}
               (set (filter (every-pred (comp #{"total" "changed"} :name)
                                        (comp #{"events" "changes" "resources"} :category))
                            metrics))))

        (is (some (fn [e]
                    (and (= 1 (:line e))
                         (= #{"notice" "notify" "hi" "class"}
                            (set (:tags e)))))
                  logs))

        (is (= 3 (count
                  (filter #(= "notice" (:level %))
                          logs))))

        (is (= 5 (count
                  (filter #(= "info" (:level %))
                          logs))))))))
