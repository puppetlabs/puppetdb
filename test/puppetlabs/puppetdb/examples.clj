(ns puppetlabs.puppetdb.examples
  (:require [puppetlabs.puppetdb.utils :as utils]))

(def catalogs
  {:empty
   {:certname         "empty.catalogs.com"
    :version          "1330463884"
    :transaction_uuid "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa"
    :catalog_uuid "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa"
    :environment      nil
    :code_id nil
    :job_id nil
    :producer_timestamp "2014-07-10T22:33:54.781Z"
    :producer "mom.com"
    :edges            #{{:source       {:type "Stage" :title "main"}
                         :target       {:type "Class" :title "Settings"}
                         :relationship :contains}
                        {:source       {:type "Stage" :title "main"}
                         :target       {:type "Class" :title "Main"}
                         :relationship :contains}}
    :resources        {{:type "Class" :title "Main"}     {:exported   false
                                                          :title      "Main"
                                                          :tags       #{"class" "main"}
                                                          :type       "Class"
                                                          :parameters {:name "main"}}
                       {:type "Class" :title "Settings"} {:exported false
                                                          :title    "Settings"
                                                          :tags     #{"settings" "class"}
                                                          :file     "/etc/puppet/modules/settings/manifests/init.pp"
                                                          :line     1
                                                          :type     "Class"}
                       {:type "Stage" :title "main"}     {:exported false
                                                          :title    "main"
                                                          :tags     #{"main" "stage"}
                                                          :type     "Stage"}}}

   :basic
   {:certname         "basic.catalogs.com"
    :code_id nil
    :job_id nil
    :transaction_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :catalog_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :environment      "DEV"
    :version          "123456789"
    :producer_timestamp "2014-07-10T22:33:54.781Z"
    :producer "mom.com"
    :edges            #{{:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "/etc/foobar"}
                         :relationship :contains}
                        {:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "/etc/foobar/baz"}
                         :relationship :contains}
                        {:source       {:type "File" :title "/etc/foobar"}
                         :target       {:type "File" :title "/etc/foobar/baz"}
                         :relationship :required-by}}
    :resources        {{:type "Class" :title "foobar"}         {:type "Class"
                                                                :title "foobar"
                                                                :exported false
                                                                :tags #{"class" "foobar"}
                                                                :parameters {}}
                       {:type "File" :title "/etc/foobar"}     {:type       "File"
                                                                :title      "/etc/foobar"
                                                                :exported   false
                                                                :file       "/tmp/foo"
                                                                :line       10
                                                                :tags       #{"file" "class" "foobar"}
                                                                :parameters {:ensure "directory"
                                                                             :group  "root"
                                                                             :user   "root"}}
                       {:type "File" :title "/etc/foobar/baz"} {:type       "File"
                                                                :title      "/etc/foobar/baz"
                                                                :exported   false
                                                                :file       "/tmp/bar"
                                                                :line       20
                                                                :tags       #{"file" "class" "foobar"}
                                                                :parameters {:ensure  "directory"
                                                                             :group   "root"
                                                                             :user    "root"
                                                                             :require "File[/etc/foobar]"}}}}

   :invalid
   {:certname         "invalid.catalogs.com"
    :code_id nil
    :job_id nil
    :transaction_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :catalog_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :version          123456789
    :edges            #{{:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "does not exist"}
                         :relationship :contains}}
    :resources        {{:type "Class" :title "foobar"}     {:type "Class" :title "foobar" :exported false}
                       {:type "File" :title "/etc/foobar"} {:type       "File"
                                                            :title      "/etc/foobar"
                                                            :exported   false
                                                            :tags       #{"file" "class" "foobar"}
                                                            :parameters {"ensure" "directory"
                                                                         "group"  "root"
                                                                         "user"   "root"}}}}})

(def v9-empty-wire-catalog
  "Basic wire catalog with a minimum number of resources/edges used/modified
   for examples of a catalog"
  {:code_id nil
   :job_id nil
   :edges
   [{:relationship "contains"
     :target       {:title "Settings" :type "Class"}
     :source       {:title "main" :type "Stage"}}
    {:relationship "contains"
     :target       {:title "main" :type "Class"}
     :source       {:title "main" :type "Stage"}}
    {:relationship "contains"
     :target       {:title "default" :type "Node"}
     :source       {:title "main" :type "Class"}}]
   :certname        "empty.wire-catalogs.com"
   :resources
   [{:exported   false
     :title      "Settings"
     :parameters {}
     :tags       ["class" "settings"]
     :type       "Class"}
    {:exported   false
     :title      "main"
     :parameters {:name "main"}
     :tags       ["class"]
     :type       "Class"}
    {:exported   false
     :title      "main"
     :parameters {:name "main"}
     :tags       ["stage"]
     :type       "Stage"}
    {:exported   false
     :title      "default"
     :parameters {}
     :tags       ["node" "default" "class"]
     :type       "Node"}]
   :version          "1332533763"
   :transaction_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
   :catalog_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
   :environment "DEV"
   :producer_timestamp "2014-07-10T22:33:54.781Z"
   :producer "mom.com"})

(def wire-catalogs
  "Catalogs keyed by version."
  {4 {:empty (-> v9-empty-wire-catalog
                 (dissoc :producer_timestamp :certname :code_id
                         :job_id :catalog_uuid :producer)
                 (assoc :name (:certname v9-empty-wire-catalog)
                        :api_version 1)
                 utils/underscore->dash-keys)}
   5 {:empty (-> v9-empty-wire-catalog
                 (assoc :name (:certname v9-empty-wire-catalog)
                        :api_version 1)
                 (dissoc :certname :job_id :code_id :catalog_uuid :producer)
                 utils/underscore->dash-keys)}
   6 {:empty (-> v9-empty-wire-catalog
                 (dissoc :job_id :code_id :catalog_uuid :producer))}
   7 {:empty (-> v9-empty-wire-catalog
                 (dissoc :catalog_uuid :producer))
      :basic
      (-> v9-empty-wire-catalog
          (assoc :certname "basic.wire-catalogs.com")
          (dissoc :catalog_uuid :producer)
          (update :resources conj {:type "File"
                                   :title "/etc/foobar"
                                   :exported false
                                   :file "/tmp/foo"
                                   :line 10
                                   :tags ["file" "class" "foobar"]
                                   :parameters {:ensure "directory"
                                                :group  "root"
                                                :user   "root"}}))}
   8 {:empty (-> v9-empty-wire-catalog
                 (dissoc :producer :job_id))
      :basic
      (-> v9-empty-wire-catalog
          (assoc :certname "basic.wire-catalogs.com")
          (dissoc :producer :job_id)
          (update :resources conj {:type "File"
                                   :title "/etc/foobar"
                                   :exported false
                                   :file "/tmp/foo"
                                   :line 10
                                   :tags ["file" "class" "foobar"]
                                   :parameters {:ensure "directory"
                                                :group  "root"
                                                :user   "root"}}))}
   9 {:empty v9-empty-wire-catalog
      :basic
      (-> v9-empty-wire-catalog
          (assoc :certname "basic.wire-catalogs.com")
          (update :resources conj {:type "File"
                                   :title "/etc/foobar"
                                   :exported false
                                   :file "/tmp/foo"
                                   :line 10
                                   :tags ["file" "class" "foobar"]
                                   :parameters {:ensure "directory"
                                                :group  "root"
                                                :source "my_file_source"
                                                :user   "root"}}))}})


(def catalog-inputs
  {:basic
   {:certname         "basic.catalogs.com"
    :catalog_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :producer_timestamp "2014-07-10T22:33:54.781Z"
    :inputs [["hiera" "puppetdb::globals::version"]
             ["hiera" "puppetdb::disable_cleartext"]
             ["hiera" "puppetdb::disable_ssl"]]
    }})

(def wire-catalog-inputs
  {1 catalog-inputs})
