(ns com.puppetlabs.puppetdb.test.http.api
  (:import (java.util.concurrent TimeUnit))
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clj-time.format :as time]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.http.api :refer :all]
            [ring.mock.request :as mock]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [com.puppetlabs.mq :as mq]
            [com.puppetlabs.puppetdb.version :as version]))

(use-fixtures :each fixt/with-test-db fixt/with-test-mq fixt/with-http-app)

(defn make-request
  "Create a ring request as it would look after passing through all of the
   application middlewares, suitable for invoking one of the api functions
   (where it assumes the middleware have already assoc'd in various attributes)."
  ([]
     (make-request {}))
  ([params]
     (make-request {} params))
  ([global-overrides params]
     {:params params
      :headers {"accept" "application/json"}
      :globals (merge {:update-server "FOO"
                       :scf-read-db          fixt/*db*
                       :scf-write-db         fixt/*db*
                       :command-mq           fixt/*mq*
                       :resource-query-limit 20000
                       :event-query-limit    20000
                       :product-name         "puppetdb"}
                      global-overrides)}))

(defn get-request
  "Creates a ring mock GET request for passing into a
   ring handler (such as fixt/*app*)"
  ([path]
     (get-request path {}))
  ([path params]
     (let [request (mock/request :get path params)
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" pl-http/json-response-content-type)))))

(defn content-type
  "Returns the content type of the ring response"
  [resp]
  (get-in resp [:headers "Content-Type"]))

(defn uuid-in-response?
  "Returns true when the response contains a properly formed
   UUID in the body of the response"
  [response]
  (instance? java.util.UUID
             (-> response
                 :body
                 (json/parse-string true)
                 :uuid
                 java.util.UUID/fromString)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest command-endpoint
  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [payload  "This is a test"
            checksum (kitchensink/utf8-string->sha1 payload)
            req (make-request {"payload" payload "checksum" checksum})
            api-resp     (command req)
            v2-resp (fixt/*app* (get-request "/v2/commands" {"payload" payload "checksum" checksum}))
            v3-resp (fixt/*app* (get-request "/v3/commands" {"payload" payload "checksum" checksum}))]
        (tu/assert-success! api-resp)
        (tu/assert-success! v2-resp)
        (tu/assert-success! v3-resp)

        (is (= (content-type api-resp)
               (content-type v2-resp)
               (content-type v3-resp)
               pl-http/json-response-content-type))
        (is (uuid-in-response? api-resp))
        (is (uuid-in-response? v2-resp))
        (is (uuid-in-response? v3-resp))))

    (testing "should return status-bad-request when missing payload"
      (let [api-resp     (command (make-request {}))
            v2-resp (fixt/*app* (get-request "/v2/commands"))
            v3-resp (fixt/*app* (get-request "/v3/commands"))]
        (is (= (:status api-resp)
               (:status v2-resp)
               (:status v3-resp)
               pl-http/status-bad-request))))

    (testing "should not do checksum verification if no checksum is provided"
      (let [api-resp (command (make-request {"payload" "my payload!"}))
            v2-resp (fixt/*app* (get-request "/v2/commands" {"payload" "my payload!"}))
            v3-resp (fixt/*app* (get-request "/v3/commands" {"payload" "my payload!"}))]
        (tu/assert-success! api-resp)
        (tu/assert-success! v2-resp)
        (tu/assert-success! v3-resp)))

    (testing "should return 400 when checksums don't match"
      (let [api-resp (command (make-request {"payload" "Testing" "checksum" "something bad"}))
            v2-resp (fixt/*app* (get-request "/v2/commands" {"payload" "Testing" "checksum" "something bad"}))
            v3-resp (fixt/*app* (get-request "/v3/commands" {"payload" "Testing" "checksum" "something bad"}))]
        (is (= (:status api-resp)
               (:status v2-resp)
               (:status v3-resp)
               pl-http/status-bad-request))))))

(deftest receipt-timestamping
  (let [good-payload       (json/generate-string {:command "my command" :version 1 :payload "{}"})
        good-checksum      (kitchensink/utf8-string->sha1 good-payload)
        bad-payload        "some test message"
        bad-checksum       (kitchensink/utf8-string->sha1 bad-payload)]
    (-> {"payload" good-payload "checksum" good-checksum}
        make-request
        command)
    (-> {"payload" bad-payload "checksum" bad-checksum}
        make-request
        command)

    (let [[good-msg bad-msg] (mq/bounded-drain-into-vec! fixt/*conn* "com.puppetlabs.puppetdb.commands" 2)
          good-command       (json/parse-string good-msg true)]
      (testing "should be timestamped when parseable"
        (let [timestamp (get-in good-command [:annotations :received])]
          (time/parse (time/formatters :date-time) timestamp)))

      (testing "should be left alone when not parseable"
        (is (= bad-msg bad-payload))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Version Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def parsed-body
  "Returns clojure data structures from the JSON body of
   ring response."
  (comp json/parse-string :body))

(defn app-with-update-server
  "Issues `request` with `overrides` merged into the request map. Useful for
   overriding things that the middleware would put in the ring request map before
   the handlers processes the request."
  [overrides request]
  (fixt/with-http-app (merge {:update-server "FOO"} overrides)
    (fn []
      (fixt/*app* request))))

(deftest test-latest-version
  (with-redefs [version/update-info
                (constantly
                 {"newer" true
                  "link" "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html"
                  "version" "100.0.0"})
                version/version (constantly "99.0.0")]
    (testing "should return 'newer'->true if product is not specified"
      (let [api-response (parsed-body (latest-version (make-request)))
            v2-response (parsed-body (app-with-update-server {} (get-request "/v2/version/latest")))
            v3-response (parsed-body (app-with-update-server {} (get-request "/v3/version/latest")))]

        (are [expected response-key] (= expected
                                        (get api-response response-key)
                                        (get v2-response response-key)
                                        (get v3-response response-key))
             true "newer"
             "100.0.0" "version"
             "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" "link")))
    (testing "should return 'newer'->true if product is 'puppetdb"
      (let [api-response (parsed-body (latest-version (make-request {:product-name "puppetdb"} {})))
            v2-response (parsed-body (app-with-update-server {:product-name "puppetdb"} (get-request "/v2/version/latest")))
            v3-response (parsed-body (app-with-update-server {:product-name "puppetdb"} (get-request "/v3/version/latest")))]
        (are [expected response-key] (= expected
                                        (get api-response response-key)
                                        (get v2-response response-key)
                                        (get v3-response response-key))
             true "newer"
             "100.0.0" "version"
             "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" "link")))
    (testing "should return 'newer'->false if product is 'pe-puppetdb"
      ;; it should *always* return false for pe-puppetdb because
      ;; we don't even want to allow checking for updates
      (let [api-response (parsed-body (latest-version (make-request {:product-name "pe-puppetdb"} {})))
            v2-response (parsed-body (app-with-update-server {:product-name "pe-puppetdb"}
                                                             (get-request "/v2/version/latest")))
            v3-response (parsed-body (app-with-update-server {:product-name "pe-puppetdb"}
                                                             (get-request "/v3/version/latest")))]
        (are [expected response-key] (= expected
                                        (get api-response response-key)
                                        (get v2-response response-key)
                                        (get v3-response response-key))
             false "newer"
             "99.0.0" "version"
             nil "link")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; MBean/Metrics Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mean-filtering
  (testing "MBean filtering"
    (testing "should pass-through serializable values"
      (is (= (filter-mbean {:key 123})
             {:key 123}))

      (testing "in nested structures"
        (is (= (filter-mbean {:key {:key 123}})
               {:key {:key 123}}))))

    (testing "should stringify unserializable objects"
      (is (= (filter-mbean {:key TimeUnit/SECONDS})
             {:key "SECONDS"}))

      (testing "in nested structures"
        (is (= (filter-mbean {:key {:key TimeUnit/SECONDS}})
               {:key {:key "SECONDS"}}))))))

(defn accepts-plain-text
  "Changes the request to handle text/plain responses"
  [req]
  (assoc-in req [:headers "accept"] "text/plain"))

(deftest metrics-set-handler
  (testing "Remote metrics endpoint"
    (testing "should return a pl-http/status-not-found for an unknown metric"
      (let [request (make-request)
            api-response ((mbean ["does_not_exist"]) request)
            v2-response (fixt/*app* (get-request "/v2/metrics/does_not_exist"))
            v3-response (fixt/*app* (get-request "/v3/metrics/does_not_exist"))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-not-found))))

    (testing "should return a pl-http/status-not-acceptable for unacceptable content type"
      (let [request (accepts-plain-text (make-request))
            api-response (list-mbeans request)
            v2-response (fixt/*app* (accepts-plain-text (get-request "/v2/metrics/mbeans")))
            v3-response (fixt/*app* (accepts-plain-text (get-request "/v3/metrics/mbeans")))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-not-acceptable))))

    (testing "should return a pl-http/status-ok for an existing metric"
      (let [request (make-request)
            api-response ((mbean ["java.lang:type=Memory"]) request)
            v2-response (fixt/*app* (get-request "/v2/metrics/mbean/java.lang:type=Memory"))
            v3-response (fixt/*app* (get-request "/v3/metrics/mbean/java.lang:type=Memory"))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-ok))
        (is (= (content-type api-response)
               (content-type v2-response)
               (content-type v3-response)
               pl-http/json-response-content-type))
        (is (true? (map? (json/parse-string (:body api-response) true))))
        (is (true? (map? (json/parse-string (:body v2-response) true))))
        (is (true? (map? (json/parse-string (:body v3-response) true))))))

    (testing "should return a list of all mbeans"
      (let [api-response (list-mbeans (make-request))
            v2-response (fixt/*app* (get-request "/v2/metrics/mbeans"))
            v3-response (fixt/*app* (get-request "/v3/metrics/mbeans"))]
        (is (= (:status api-response)
               (:status v2-response)
               (:status v3-response)
               pl-http/status-ok))
        (is (= (content-type api-response)
               (content-type v2-response)
               (content-type v3-response)
               pl-http/json-response-content-type))

        ;; Retrieving all the resulting mbeans should work
        (let [api-mbeans (json/parse-string (:body api-response))
              v2-mbeans (json/parse-string (:body v2-response))
              v3-mbeans (json/parse-string (:body v3-response))]

          (is (map? api-mbeans))
          (is (map? v2-mbeans))
          (is (map? v3-mbeans))

          (doseq [[name uri] (take 100 api-mbeans)
                  :let [response ((mbean [name]) (make-request))]]

            (is (= (:status response pl-http/status-ok)))
            (is (= (content-type response) pl-http/json-response-content-type)))

          (doseq [[name uri] (take 100 v2-mbeans)
                  :let [response (fixt/*app* (get-request (str "/v2" uri))) ]]
            (is (= (:status response pl-http/status-ok)))
            (is (= (content-type response) pl-http/json-response-content-type)))

          (doseq [[name uri] (take 100 v3-mbeans)
                  :let [response (fixt/*app* (get-request (str "/v3" uri)))]]
            (is (= (:status response pl-http/status-ok)))
            (is (= (content-type response) pl-http/json-response-content-type))))))))
