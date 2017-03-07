(ns puppetlabs.puppetdb.integration.reports.basic-event-query
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clj-time.core :refer [now]]))

(deftest ^:integration basic-event-query
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (let [start-time (now)]
      ; Ensure at least 1 ms has passed
      (Thread/sleep 1)
      (testing "Initial agent run, to populate puppetdb with data to query"
        (int/run-puppet-as "my_agent" ps pdb
                           (str "notify { 'hi':"
                                "  message => 'Hi my_agent' "
                                "}")))
      (let [result (int/pql-query pdb (format (str "events [old_value, new_value] "
                                                   "{ timestamp > '%s' "
                                                   " and certname = 'my_agent'"
                                                   " and resource_type = 'Notify'"
                                                   " and status = 'success'"
                                                   " and property ~ '^[Mm]essage$'"
                                                   " and message ~ 'Hi my_agent' "
                                                   "}")
                                              (int/query-timestamp-str start-time)))]
        (is (= [{:old_value "absent"
                 :new_value "Hi my_agent"}]
               result))))))
