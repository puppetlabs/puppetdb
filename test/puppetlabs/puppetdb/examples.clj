(ns puppetlabs.puppetdb.examples)

(def catalogs
  {:empty
   {:name             "empty.catalogs.com"
    :api_version      1
    :version          "1330463884"
    :transaction_uuid nil
    :environment      nil
    :producer_timestamp nil
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
   {:name             "basic.catalogs.com"
    :api_version      1
    :transaction_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :environment      "DEV"
    :version          "123456789"
    :producer_timestamp nil
    :edges            #{{:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "/etc/foobar"}
                         :relationship :contains}
                        {:source       {:type "Class" :title "foobar"}
                         :target       {:type "File" :title "/etc/foobar/baz"}
                         :relationship :contains}
                        {:source       {:type "File" :title "/etc/foobar"}
                         :target       {:type "File" :title "/etc/foobar/baz"}
                         :relationship :required-by}}
    :resources        {{:type "Class" :title "foobar"}         {:type "Class" :title "foobar" :exported false}
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
   {:name             "invalid.catalogs.com"
    :api_version      1
    :transaction_uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
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

(def v6-empty-wire-catalog
  "Basic wire catalog with a minimum number of resources/edges used/modified
   for examples of a catalog"
  {:edges
   [{:relationship "contains"
     :target       {:title "Settings" :type "Class"}
     :source       {:title "main" :type "Stage"}}
    {:relationship "contains"
     :target       {:title "main" :type "Class"}
     :source       {:title "main" :type "Stage"}}
    {:relationship "contains"
     :target       {:title "default" :type "Node"}
     :source       {:title "main" :type "Class"}}]
   :name        "empty.wire-catalogs.com"
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
   :environment "DEV"
   :producer_timestamp "2014-07-10T22:33:54.781Z"})

(def wire-catalogs
  "Catalogs keyed by version."
  {6 {:empty v6-empty-wire-catalog
      :basic
      (-> v6-empty-wire-catalog
          (assoc :name "basic.wire-catalogs.com")
          (update-in [:resources] conj {:type       "File"
                                        :title      "/etc/foobar"
                                        :exported   false
                                        :file       "/tmp/foo"
                                        :line       10
                                        :tags       ["file" "class" "foobar"]
                                        :parameters {:ensure "directory"
                                                     :group  "root"
                                                     :user   "root"}}))}})
