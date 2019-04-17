(ns puppetlabs.puppetdb.http.inventory-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [clojure.walk :refer [stringify-keys]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.http :as http]
            [flatland.ordered.map :as omap]
            [puppetlabs.puppetdb.testutils.http :refer [*app*
                                                        query-response
                                                        deftest-http-app]]
            [puppetlabs.puppetdb.testutils :refer [get-request
                                                   assert-success!]]
            [puppetlabs.puppetdb.time :refer [now to-string to-timestamp]]))

(def inventory-endpoints [[:v4 "/v4/inventory"]])

(def inventory1
  {:certname "bar.com"
   :timestamp nil
   :environment "DEV"
   :trusted {:foo {:baz "bar"}}
   :facts {:domain "testing.com"
           :hostname "bar.com"
           :trusted {:foo {:baz "bar"}}
           :kernel "Linux"
           :array_fact {:foo ["biz" "baz"]}
           :my_structured_fact {:foo {:bar 1
                                      :baz 2}}
           :operatingsystem "Debian"
           :some_version "1.3.7+build.11.e0f985a"
           :uptime_seconds 4000
           :my_weird_fact {:blah {:dotted.thing {:dashed-thing {(keyword "quoted\"thing") "foo"}}}}
           :double_quote "foo\"bar"
           :backslash "foo\\bar"}})

(def inventory2
  {:certname "foo.com"
   :timestamp nil
   :environment "DEV"
   :trusted {:foo {:baz "biz"}}
   :facts {:domain "testing.com"
           :hostname "foo.com"
           :kernel "Windows"
           :trusted {:foo {:baz "biz"}}
           :my_structured_fact {:foo {:bar 1
                                      :baz 3}}
           :operatingsystem "Debian"
           :some_version "1.3.7+build.11.e0f985a"
           :uptime_seconds 4000}})

(def inventory3
  {:certname "baz.com"
   :timestamp nil
   :environment "DEV"
   :trusted {:foo {:baz "qbiz"}}
   :facts {:trusted {:foo {:baz "qbiz"}}}})

(defn queries-and-results
  [timestamp]
  (let [response1 (assoc inventory1 :timestamp (to-string timestamp))
        response2 (assoc inventory2 :timestamp (to-string timestamp))
        response3 (assoc inventory3 :timestamp (to-string timestamp))]

    (omap/ordered-map
     nil
     #{response1 response2 response3}

     ["=" "certname" "bar.com"]
     #{response1}

     ["=" "facts.kernel" "Linux"]
     #{response1}

     ["~" "facts.kernel" "Li.*"]
     #{response1}

     ["=" "facts.array_fact.foo[1]" "baz"]
     #{response1}

     ["~" "facts.array_fact.foo[0]" "bi.*"]
     #{response1}

     ;; ["=" "facts.match(\".*\")" "Debian"]
     ;; #{response1 response2}

     ;; ["=" "facts.match(\".*\")" "Windows"]
     ;; #{response2}

     ["=" "facts.my_structured_fact.foo.baz" 3]
     #{response2}

     ["<=" "facts.my_structured_fact.foo.baz" 4]
     #{response1 response2}

     ["=" "trusted.foo.baz" "bar"]
     #{response1}

     ["~" "trusted.foo.baz" "ba.*"]
     #{response1}

     ["=" "facts.my_weird_fact.blah.\"dotted.thing\".\"dashed-thing\".\"quoted\"thing\"" "foo"]
     #{response1}

     ["~" "facts.kernel" "^Linux$"]
     #{response1}

     ["~" "facts.trusted.foo.baz" "^biz"]
     #{response2}

     ["~" "facts.trusted.foo.baz" "biz$"]
     #{response2 response3}

     ["~" "facts.domain" "^test.*"]
     #{response1 response2}

     ["~" "facts.domain" "com$"]
     #{response1 response2}

     ["~" "facts.double_quote" "^foo\"bar$"]
     #{response1}

     ["~" "facts.backslash" "^foo\\\\bar$"]
     #{response1})))

(deftest-http-app inventory-queries
  [[version endpoint] inventory-endpoints
   method [:get :post]]

  (let [facts1 (:facts inventory1)
        facts2 (:facts inventory2)
        facts3 (:facts inventory3)
        timestamp (now)]
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "foo.com")
      (scf-store/add-certname! "bar.com")
      (scf-store/add-certname! "baz.com")
      (scf-store/add-facts! {:certname "bar.com"
                             :values (stringify-keys facts1)
                             :timestamp timestamp
                             :environment "DEV"
                             :producer_timestamp timestamp
                             :producer "bar1"})
      (scf-store/add-facts! {:certname "foo.com"
                             :values (stringify-keys facts2)
                             :timestamp timestamp
                             :environment "DEV"
                             :producer_timestamp timestamp
                             :producer "bar1"})
      (scf-store/add-facts! {:certname "baz.com"
                             :values (stringify-keys facts3)
                             :timestamp timestamp
                             :environment "DEV"
                             :producer_timestamp timestamp
                             :producer "bar1"}))

    (testing "query without param should not fail"
      (let [response (query-response method endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "inventory queries"
      (testing "well-formed queries"
        (doseq [[query result] (queries-and-results timestamp)]
          (testing (format "Query %s" query)
            (let [request (if query
                            (get-request endpoint (json/generate-string query))
                            (get-request endpoint))
                  {:keys [status body headers]} (*app* request)]
              (is (= status http/status-ok))
              (is (http/json-utf8-ctype? (headers "Content-Type")))
              (is (= (set result)
                     (set (json/parse-string (slurp body) true)))))))))))
