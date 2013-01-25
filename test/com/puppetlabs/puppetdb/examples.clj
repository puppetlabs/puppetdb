(ns com.puppetlabs.puppetdb.examples
  (:use [com.puppetlabs.puppetdb.catalog :only [catalog-version]]))

(def catalogs
  {:empty
   {:certname         "empty.catalogs.com"
    :puppetdb-version catalog-version
    :api-version      1
    :version          "1330463884"
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
                                                          :type     "Class"}
                       {:type "Stage" :title "main"}     {:exported false
                                                          :title    "main"
                                                          :tags     #{"main" "stage"}
                                                          :type     "Stage"}}}

   :basic
   {:certname         "basic.catalogs.com"
    :puppetdb-version catalog-version
    :api-version      1
    :version          "123456789"
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
   {:certname         "invalid.catalogs.com"
    :puppetdb-version catalog-version
    :api-version      1
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

(def wire-catalogs
  {1 {:empty
      {:metadata      {:api_version 1}
       :document_type "Catalog"
       :data
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
        :tags        ["settings" "default" "node"]
        :classes     ["settings" "default"]
        :environment "production"
        :version     1332533763}}}

   2 {:empty
      {:metadata      {:api_version 1}
       :document_type "Catalog"
       :data
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
        :version     1332533763}}}})
