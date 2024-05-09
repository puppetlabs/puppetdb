(ns puppetlabs.puppetdb.dashboard
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.utils :refer [call-unless-shutting-down]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [ring.util.response :as rr]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-utils :refer [get-mbean]]
            [puppetlabs.puppetdb.http :as http]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]))

;;; Dashboard data endpoint

(def meter-def-schema
  {:description s/Str
   :addendum s/Str
   :mbean s/Str
   :format s/Str
   (s/optional-key :clampToZero) s/Num
   :value-path [s/Keyword]
   (s/optional-key :value-scale) s/Num})

(def meter-schema
  (-> meter-def-schema
      (dissoc :value-path :value-scale)
      (merge {:id s/Str
              :value (s/maybe s/Num)})))

(defn default-meter-defs []
  [{:description (tru "JVM Heap")
    :addendum (tru "bytes")
    :mbean "java.lang:type=Memory"
    :format ".3s"
    :value-path [:HeapMemoryUsage :used]}

   {:description (tru "Active Nodes")
    :addendum (tru "in the population")
    :mbean "puppetlabs.puppetdb.population:name=num-active-nodes"
    :format ""
    :value-path [:Value]}

   {:description (tru "Inactive Nodes")
    :addendum (tru "in the population")
    :mbean "puppetlabs.puppetdb.population:name=num-inactive-nodes"
    :format ""
    :value-path [:Value]}

   {:description (tru "Resources")
    :addendum (tru "in the population")
    :mbean "puppetlabs.puppetdb.population:name=num-resources"
    :format ""
    :value-path [:Value]}

   {:description (tru "Resource duplication")
    :addendum (tru "% of resources stored")
    :mbean "puppetlabs.puppetdb.population:name=pct-resource-dupes"
    :format ".1%"
    :value-path [:Value]}

   {:description (tru "Catalog duplication")
    :addendum (tru "% of catalogs encountered")
    :mbean "puppetlabs.puppetdb.storage:name=duplicate-pct"
    :format ".1%"
    :value-path [:Value]}

   {:description (tru "Command Queue")
    :addendum (tru "depth")
    :mbean "puppetlabs.puppetdb.mq:name=global.depth"
    :format "s"
    :value-path [:Count]}

   {:description (tru "Command Processing")
    :addendum (tru "sec/command")
    :mbean "puppetlabs.puppetdb.mq:name=global.processing-time"
    :format ".3s"
    :value-path [:50thPercentile]
    :value-scale 1/1000
    :clampToZero 0.001}

   {:description (tru "Command Processing")
    :addendum (tru "command/sec")
    :mbean "puppetlabs.puppetdb.mq:name=global.processed"
    :format ".3s" ;; TODO: clampToZero((".3s") 0.001)
    :value-path [:FiveMinuteRate]}

   {:description (tru "Processed")
    :addendum (tru "since startup")
    :mbean "puppetlabs.puppetdb.mq:name=global.processed"
    :format ""
    :value-path [:Count]}

   {:description (tru "Retried")
    :addendum (tru "since startup")
    :mbean "puppetlabs.puppetdb.mq:name=global.retried"
    :format ""
    :value-path [:Count]}

   {:description (tru "Discarded")
    :addendum (tru "since startup")
    :mbean "puppetlabs.puppetdb.mq:name=global.discarded"
    :format ""
    :value-path [:Count]}

   {:description (tru "Rejected")
    :addendum (tru "since startup")
    :mbean "puppetlabs.puppetdb.mq:name=global.fatal"
    :format ""
    :value-path [:Count]}

   {:description (tru "Enqueueing")
    :addendum (tru "service time seconds")
    :mbean "puppetlabs.puppetdb.http:name=/pdb/cmd/v1.service-time"
    :format ".3s"
    :value-path [:50thPercentile]
    :value-scale 1/1000}

   {:description (tru "Command Persistence")
    :addendum (tru "Message persistence time milliseconds")
    :mbean "puppetlabs.puppetdb.mq:name=global.message-persistence-time"
    :format ".2s"
    :value-path [:FiveMinuteRate]}

   {:description (tru "Collection Queries")
    :addendum (tru "service time seconds")
    :mbean "puppetlabs.puppetdb.http:name=/pdb/query/v4/resources.service-time"
    :format ".3s"
    :value-path [:50thPercentile]
    :value-scale 1/1000}

   {:description (tru "DB Compaction")
    :addendum (tru "round trip time seconds")
    :mbean "puppetlabs.puppetdb.storage:name=gc-time"
    :format ".3s"
    :value-path [:50thPercentile]
    :value-scale 1/1000}

   {:description (tru "DLO Size on Disk")
    :addendum (tru "bytes")
    :mbean "puppetlabs.puppetdb.dlo:name=global.filesize"
    :format ".3s"
    :value-path [:Value]}

   {:description (tru "Discarded Messages")
    :addendum (tru "to be reviewed")
    :mbean "puppetlabs.puppetdb.dlo:name=global.messages"
    :format ""
    :value-path [:Value]}])

(defn meter-id [meter-def]
  (format "%x"
          (hash (select-keys meter-def [:description :addendum :mbean :format :value-path]))))

(pls/defn-validated get-dashboard-data :- [meter-schema]
  [meter-defs]
  (for [{:keys [mbean value-path value-scale] :or {value-scale 1} :as m} meter-defs]
    (-> m
        (dissoc :value-path :value-scale)
        (assoc :value (some-> (get-in (get-mbean mbean) value-path)
                              (* value-scale))
               :id (meter-id m)))))

(defn build-app [meter-defs-fn]
  (mid/make-pdb-handler
   (cmdi/routes
    (cmdi/context "/data"
                  (cmdi/GET "" []
                            (http/json-response
                             (get-dashboard-data (meter-defs-fn))))))))

;;; Dashboard redirect for "/" (not "/pdb", cf. pdb-core-routes)

(def dashboard-routes
  (cmdi/context "/"
                (cmdi/GET "" []
                          (rr/redirect "/pdb/dashboard/index.html"))))

(defservice dashboard-redirect-service
  [[:ShutdownService get-shutdown-reason]
   [:WebroutingService add-ring-handler get-route]]

  (start
   [this context]
   (call-unless-shutting-down
    "PuppetDB dashboard start" (get-shutdown-reason) context
    #(do
       (log/info (trs "Redirecting / to the PuppetDB dashboard"))
       (add-ring-handler this (mid/make-pdb-handler dashboard-routes))
       context))))
