(ns puppetlabs.puppetdb.admin-clean-test
  (:require [clj-http.client :as http-client]
            [clojure.test :refer :all]
            [overtone.at-at :as at-at]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [*server* with-single-quiet-pdb-instance]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-context]])
  (:import
   [java.util.concurrent CyclicBarrier]))

(defn- post-admin [path form]
  (http-client/post (str (utils/base-url->str (svc-utils/pdb-admin-url))
                         "/"
                         path)
                    {:body (json/generate-string form)
                     :throw-exceptions false
                     :content-type :json
                     :character-encoding "UTF-8"
                     :accept :json}))

(defn- checked-admin-post [path form]
  (let [result (post-admin path form)]
    (is (= http/status-ok (:status result)))
    (when-not (= http/status-ok (:status result))
      (binding [*out* *err*]
        (clojure.pprint/pprint result)))))

(defn- clean-cmd [what]
  {:command "clean" :version 1 :payload what})

(defn- post-clean [what]
  (post-admin "cmd" (clean-cmd what)))

(defmacro with-blocked-clean [what in-clean test-finished & body]
  `(do
     (utils/noisy-future
      (checked-admin-post "cmd" (clean-cmd ~what)))
     (try
       (.await ~in-clean)
       ~@body
       (finally
         (.await ~test-finished)))))

(deftest admin-clean-basic
  (with-single-quiet-pdb-instance
    (let [pdb (get-service *server* :PuppetDBServer)]
      ;; Stop intermittent cleaning so it can't interfere
      (at-at/stop-and-reset-pool! (:job-pool (service-context pdb)))
      (is (= http/status-ok (:status (post-clean []))))
      (is (= http/status-ok (:status (post-clean ["expire_nodes"]))))
      (is (= http/status-ok (:status (post-clean ["purge_nodes"]))))
      (is (= http/status-ok (:status (post-clean ["purge_reports"]))))
      (is (= http/status-ok (:status (post-clean ["other"]))))
      (is (= http/status-bad-request (:status (post-clean ["?"])))))))

(deftest admin-clean-competition
  (with-single-quiet-pdb-instance
    (let [pdb (get-service *server* :PuppetDBServer)
          orig-clean cli-svc/clean-up
          in-clean (CyclicBarrier. 2)
          test-finished (CyclicBarrier. 2)]
      ;; Stop intermittent cleaning so it can't interfere
      (at-at/stop-and-reset-pool! (:job-pool (service-context pdb)))
      (with-redefs [cli-svc/clean-up (fn [& args]
                                       (.await in-clean)
                                       (.await test-finished)
                                       (apply orig-clean args))]
        (with-blocked-clean [] in-clean test-finished
          (is (= http/status-conflict (:status (post-clean [])))))))))
