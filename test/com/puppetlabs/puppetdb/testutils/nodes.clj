(ns com.puppetlabs.puppetdb.testutils.node
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store])
  (:use [com.puppetlabs.puppetdb.examples]
        [clj-time.core :only [now]]))

(defn store-example-nodes
  []
  (let [web1 "web1.example.com"
        web2 "web2.example.com"
        puppet "puppet.example.com"
        db "db.example.com"
        catalog (:empty catalogs)
        web1-catalog (update-in catalog [:resources] conj {{:type "Class" :title "web"} {:type "Class" :title "web1" :exported false}})
        puppet-catalog  (update-in catalog [:resources] conj {{:type "Class" :title "puppet"} {:type "Class" :title "puppetmaster" :exported false}})
        db-catalog  (update-in catalog [:resources] conj {{:type "Class" :title "db"} {:type "Class" :title "mysql" :exported false}})]
    (scf-store/add-certname! web1)
    (scf-store/add-certname! web2)
    (scf-store/add-certname! puppet)
    (scf-store/add-certname! db)
    (scf-store/add-facts! web1 {"ipaddress" "192.168.1.100" "hostname" "web1" "operatingsystem" "Debian" "uptime_seconds" 10000} (now))
    (scf-store/add-facts! web2 {"ipaddress" "192.168.1.101" "hostname" "web2" "operatingsystem" "Debian" "uptime_seconds" 13000} (now))
    (scf-store/add-facts! puppet {"ipaddress" "192.168.1.110" "hostname" "puppet" "operatingsystem" "RedHat" "uptime_seconds" 15000} (now))
    (scf-store/add-facts! db {"ipaddress" "192.168.1.111" "hostname" "db" "operatingsystem" "Debian"} (now))
    (scf-store/replace-catalog! (assoc web1-catalog :certname web1) (now))
    (scf-store/replace-catalog! (assoc puppet-catalog :certname puppet) (now))
    (scf-store/replace-catalog! (assoc db-catalog :certname db) (now))
    {:web1    web1
     :web2    web2
     :db      db
     :puppet  puppet}))
