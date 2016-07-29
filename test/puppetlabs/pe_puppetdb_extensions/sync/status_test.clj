(ns puppetlabs.pe-puppetdb-extensions.sync.status-test
  (:require [puppetlabs.pe-puppetdb-extensions.sync.status :refer :all]
            [clojure.test :refer :all]))

(deftest alerts-test
  (are [sync-status expected] (= expected (alerts sync-status))
    {:state :idle}
    []

    {:state :error
     :error-message "foo"}
    [{:severity :warning, :message "foo"}]

    {:state :syncing
     :entity-status {:reports {:phase :summary}}}
    [{:severity :info, :message "Reconciling reports"}]

    {:state :syncing
     :entity-status {:reports {:phase :transfer, :total 500}}}
    [{:severity :info, :message "Transferring 500 reports"}]

    {:state :syncing
     :entity-status {:reports {:phase :transfer, :total 500, :processed 123}}}
    [{:severity :info, :message "Transferring reports (123/500)"}]

    {:state :syncing
     :entity-status {:catalogs {:phase :summary}}}
    [{:severity :info, :message "Reconciling catalogs"}]

    {:state :syncing
     :entity-status {:factsets {:phase :summary}}}
    [{:severity :info, :message "Reconciling facts"}]

    {:state :syncing
     :entity-status {:nodes {:phase :summary}}}
    [{:severity :info, :message "Reconciling node deactivations"}]


    {:state :syncing
     :entity-status {:reports {:phase :transfer, :total 500, :processed 123}
                     :catalogs {:phase :summary}}}
    [{:severity :info, :message "Transferring reports (123/500)"}
     {:severity :info, :message "Reconciling catalogs"}]))

