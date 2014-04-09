(ns com.puppetlabs.puppetdb.test.http.experimental.planetarium-catalog
  (:require [cheshire.core :as json]
            ring.middleware.params
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]
        [com.puppetlabs.puppetdb.testutils :only [get-request]]))

(use-fixtures :each with-test-db with-http-app)

(def c-t pl-http/json-response-content-type)

(defn get-response
  ([]      (get-response nil))
  ([node] (*app* (get-request (str "/experimental/planetarium-catalog/" node)))))

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
    (scf-store/add-certname! (:name basic-catalog))
    (scf-store/add-certname! (:name empty-catalog))
    (scf-store/replace-catalog! basic-catalog (now))
    (scf-store/replace-catalog! empty-catalog (now))

    (testing "should return the catalog if it's present"
      (is-response-equal (get-response (:name empty-catalog))
        {"name" (:name empty-catalog)
         "resources" {"Class[Main]" {"certname"   (:name empty-catalog)
                                     "type"       "Class"
                                     "title"      "Main"
                                     "resource"   "4e52e8387f0766e007a450c63ee7a37b9c16a016"
                                     "exported"   false
                                     "file" nil
                                     "line" nil
                                     "count"      1
                                     "tags"       ["class" "main"]
                                     "environment" nil
                                     "parameters" {"name" "main"}}
                     "Class[Settings]" {"certname"   (:name empty-catalog)
                                        "type"       "Class"
                                        "title"      "Settings"
                                        "resource"   "e07ed40565f4d82e468b47b627df444557e132f6"
                                        "exported"   false
                                        "file"       "/etc/puppet/modules/settings/manifests/init.pp"
                                        "line"       1
                                        "count"      1
                                        "tags"       ["class" "settings"]
                                        "environment" nil
                                        "parameters" {}}
                     "Stage[main]" {"certname"   (:name empty-catalog)
                                    "type"       "Stage"
                                    "title"      "main"
                                    "resource"   "76c4350dca7f6dc2f900be31b7ac5eecf6c54b4e"
                                    "exported"   false
                                    "file" nil
                                    "line" nil
                                    "count"      1
                                    "tags"       ["main" "stage"]
                                    "environment" nil
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
        (is (= pl-http/status-not-found (:status response)))
        (is (= "missing node") (:body response))))))
