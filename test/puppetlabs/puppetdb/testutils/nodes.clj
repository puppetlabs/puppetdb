(ns puppetlabs.puppetdb.testutils.nodes
  (:require [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.examples :refer :all]
            [puppetlabs.puppetdb.zip :as zip]
            [puppetlabs.puppetdb.reports :as report]
            [clj-time.core :refer [now plus seconds]]))

(defn change-certname
  "Changes [:certname certname] anywhere in `data` to `new-certname`"
  [data new-certname]
  (:node (zip/post-order-transform
           (zip/tree-zipper data) [(fn [node]
                                     (when (and (map? node)
                                                (contains? node :certname))
                                       (assoc node :certname new-certname)))])))

(defn basic-report-for-node
  "Creates a report from `reports` for `node-name`"
  [node-name]
  (-> (:basic reports)
      (change-certname node-name)
      (assoc :end_time (now))
      report/report-query->wire-v6))

(defn store-example-nodes
  []
  (let [web1 "web1.example.com"
        web2 "web2.example.com"
        puppet "puppet.example.com"
        db "db.example.com"
        catalog (:empty catalogs)
        web1-catalog (update-in catalog [:resources] conj
                                {{:type "Class" :title "web"}
                                 {:type "Class" :title "web1" :exported false}})
        puppet-catalog  (update-in catalog [:resources]
                                   conj {{:type "Class" :title "puppet"}
                                         {:type "Class" :title "puppetmaster"
                                          :exported false}})
        db-catalog  (update-in catalog [:resources]
                               conj {{:type "Class" :title "db"}
                                     {:type "Class" :title "mysql" :exported false}})]
    (scf-store/add-certname! web1)
    (scf-store/add-certname! web2)
    (scf-store/add-certname! puppet)
    (scf-store/add-certname! db)
    (scf-store/add-facts! {:certname web1
                           :values {"ipaddress" "192.168.1.100"
                                    "hostname" "web1"
                                    "operatingsystem" "Debian"
                                    "uptime_seconds" 10000}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-facts! {:certname web2
                           :values {"ipaddress" "192.168.1.101"
                                    "hostname" "web2"
                                    "operatingsystem" "Debian"
                                    "uptime_seconds" 13000}
                           :timestamp (plus (now) (seconds 1))
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-facts! {:certname puppet
                           :values {"ipaddress" "192.168.1.110"
                                    "hostname" "puppet" "operatingsystem"
                                    "RedHat" "uptime_seconds" 15000}
                           :timestamp (plus (now) (seconds 2))
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-facts! {:certname db
                           :values {"ipaddress" "192.168.1.111"
                                    "hostname" "db"
                                    "operatingsystem" "Debian"}
                           :timestamp (plus (now) (seconds 3))
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/replace-catalog! (assoc web1-catalog :certname web1) (now))
    (scf-store/replace-catalog! (assoc puppet-catalog :certname puppet) (plus (now) (seconds 1)))
    (scf-store/replace-catalog! (assoc db-catalog :certname db) (plus (now) (seconds 2)))
    (scf-store/add-report! (basic-report-for-node web1) (now))
    (scf-store/add-report! (basic-report-for-node puppet) (plus (now) (seconds 2)))
    (scf-store/add-report! (basic-report-for-node db) (plus (now) (seconds 3)))
    {:web1    web1
     :web2    web2
     :db      db
     :puppet  puppet}))
