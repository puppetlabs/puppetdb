(ns puppetlabs.puppetdb-sync.testutils
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context service-id]]
            [puppetlabs.puppetdb-sync.services :refer [puppetdb-sync-service]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [compojure.core :refer [context POST routes ANY]]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.testutils.jetty :as jutils]))

(def requests (atom []))

(def request-catcher
  "Takes any POST and saves the request map in the `requests` atom for
  verification that the correct call had been made"
  (context "/request-catcher" []
           (routes
            (POST "/v1/consume-request" {:as req}
                  (swap! requests conj (update-in req [:body] slurp))
                  {:status 200 :body "Success"}))))

(defn request-catcher-fixture
  "Resets the `requests` atom used by the request-catcher service,
  useful as a clojure.test fixture"
  [f]
  (reset! requests [])
  (f))

(defservice request-catcher-service
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler]]
  (start [this context]
         (let [port (get-in-config [:webserver :default :port])]
           (add-ring-handler this request-catcher)
           context)))

(defprotocol CannedResponse
  (expect-response [this path response] "Adds `path` with `response` to the canned response map"))

(defn canned-response
  "Service that matches the incoming route with the canned-responses
  map returning the value found at that route"
  [service]
  (context "/canned-response" []
           (routes
            (ANY "*" {:as req}
                 (let [canned-responses @(::canned-responses (service-context service))]
                   (get canned-responses (:uri req)))))))


(defservice canned-response-service
  CannedResponse
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler]]
  (start [this context]
         (let [port (get-in-config [:webserver :default :port])]
           (add-ring-handler this (canned-response this))
           (assoc context ::canned-responses (atom {}))))
  (expect-response [this path response]
                   (swap! (::canned-responses (service-context this)) assoc path response)))

(defmacro with-puppetdb-instance
  "Same as the core puppetdb-instance call but adds in the sync
  service and the request-catcher/canned-response service"
  [config & body]
  `(jutils/puppetdb-instance
    ~config
    [puppetdb-sync-service request-catcher-service canned-response-service]
    (fn [] ~@body)))

(defn open-port-num
  "Returns a currently open port number"
  []
  (let [s (java.net.ServerSocket. 0)
        local-port (.getLocalPort s)]
    (.close s)
    local-port))

(defn sync-config
  "Returns a default TK config setup for sync testing"
  []
  (-> (jutils/create-config)
      (assoc-in [:jetty :port] (open-port-num))
      (assoc :web-router-service {:puppetlabs.puppetdb.cli.services/puppetdb-service "/pdb"
                                  :puppetlabs.puppetdb-sync.services/puppetdb-sync-service "/sync"
                                  :puppetlabs.puppetdb-sync.testutils/request-catcher-service "/request-catcher"
                                  :puppetlabs.puppetdb-sync.testutils/canned-response-service "/canned-response"})))
