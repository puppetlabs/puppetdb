(ns com.puppetlabs.puppetdb.test.http.catalog
  (:require [cheshire.core :as json]
            ring.middleware.params
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]))

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-request
  [path]
  (let [request (request :get path)]
    (update-in request [:headers] assoc "Accept" c-t)))

(defn get-response
  ([]      (get-response nil))
  ([node] (*app* (get-request (str "/experimental/catalog/" node)))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= pl-http/status-ok   (:status response)))
  (is (= c-t (get-in response [:headers "Content-Type"])))
  (is (= (when-let [body (:body response)]
           (let [body (json/parse-string body)
                 resources (body "resources")]
             (-> body
               (update-in ["edges"] set)
               (assoc "resources" (into {} (for [[ref resource] resources]
                                             [ref (update-in resource ["tags"] sort)]))))))
         body)))

(deftest catalog-retrieval
  (let [basic-catalog (:basic catalogs)
        empty-catalog (:empty catalogs)]
    (scf-store/add-certname! (:certname basic-catalog))
    (scf-store/add-certname! (:certname empty-catalog))
    (scf-store/store-catalog-for-certname! basic-catalog (now))
    (scf-store/store-catalog-for-certname! empty-catalog (now))

    (testing "should return the catalog if it's present"
      (is-response-equal (get-response (:certname empty-catalog))
        {"name" (:certname empty-catalog)
         "resources" {"Class[Main]" {"certname"   (:certname empty-catalog)
                                     "type"       "Class"
                                     "title"      "Main"
                                     "resource"   "fc22ffa0a8128d5676e1c1d55e04c6f55529f04c"
                                     "exported"   false
                                     "sourcefile" nil
                                     "sourceline" nil
                                     "count"      1
                                     "tags"       ["class" "main"]
                                     "parameters" {"name" "main"}}
                     "Class[Settings]" {"certname"   (:certname empty-catalog)
                                        "type"       "Class"
                                        "title"      "Settings"
                                        "resource"   "cc1869f0f075fc3c3e5828de9e92d65a0bf8d9ff"
                                        "exported"   false
                                        "sourcefile" nil
                                        "sourceline" nil
                                        "count"      1
                                        "tags"       ["class" "settings"]
                                        "parameters" {}}
                     "Stage[main]" {"certname"   (:certname empty-catalog)
                                    "type"       "Stage"
                                    "title"      "main"
                                    "resource"   "124522a30c56cb9e4bbc66bae4c2515cda6ec889"
                                    "exported"   false
                                    "sourcefile" nil
                                    "sourceline" nil
                                    "count"      1
                                    "tags"       ["main" "stage"]
                                    "parameters" {}}}
         "edges" #{{"source" {"type" "Stage" "title" "main"}
                   "target" {"type" "Class" "title" "Settings"}
                   "relationship" "contains"}
                  {"source" {"type" "Stage" "title" "main"}
                   "target" {"type" "Class" "title" "Main"}
                   "relationship" "contains"}}}))

    (testing "should return status-not-found if the catalog isn't found"
      (let [response (get-response "non-existent-node")]
        (is (= pl-http/status-not-found (:status response)))
        (is (= {:error "Could not find catalog for non-existent-node"}
               (json/parse-string (:body response) true)))))

    (testing "should fail if no node is specified"
      (let [response (get-response)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "missing node") (:body response))))))
