(ns com.puppetlabs.cmdb.test.http.command
  (:require [com.puppetlabs.cmdb.scf.storage :as scf-store]
            [com.puppetlabs.cmdb.http.server :as server]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
         ring.mock.request
         [com.puppetlabs.cmdb.testutils]
         [com.puppetlabs.cmdb.testutils :only [test-db]]
         [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]))

(def *app* nil)
(def *conn* nil)

(use-fixtures :each (fn [f]
                      (with-test-broker "test" conn
                        (let [db (test-db)]
                          (binding [*app* (server/build-app
                                           {:scf-db db
                                            :command-mq {:connection-string "vm://test"
                                                         :endpoint "com.puppetlabs.cmdb.commands"}})
                                    *conn* conn]
                            (sql/with-connection db
                              (migrate!)
                              (f)))))))

(defn make-request
  [post-body]
  (let [request (request :post "/commands")]
    (-> request
        (assoc-in [:headers "accept"] "application/json")
        (body post-body))))

(deftest command-endpoint
  (testing "Commands submitted via REST"

    (testing "should work when well-formed"
      (let [req  (make-request {:payload "This is a test"})
            resp (*app* req)]
        (is (= (:status resp) 200))
        (is (= (get-in resp [:headers "Content-Type"]) "application/json"))
        (is (= (json/parse-string (:body resp)) true))))

    (testing "should return 400 when malformed"
      (let [req  (make-request {})
            resp (*app* req)]
        (is (= (:status resp) 400))
        (is (= (get-in resp [:headers "Content-Type"]) "application/json"))))))
