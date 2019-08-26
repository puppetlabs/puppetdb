(ns puppetlabs.puppetdb.testutils.catalog-inputs
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.command :refer [process-command!]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]
            [clojure.test :refer [is]]
            [puppetlabs.puppetdb.time :as time]))

(def command-version 1)

(defn sample-input-cmds []
  ;; For now, the tests expect all the non-input fields to be unique
  ;; among these test hosts, and for there to be no optional fields.
  {"host-1"
   {:certname "host-1"
    :producer_timestamp (time/now)
    :catalog_uuid "6c2a2b15-1c1e-4081-a723-e9b40989d1e5"
    :inputs [["hiera" "puppetdb::globals::version"]
             ["hiera" "puppetdb::disable_cleartext"]]}
   "host-2"
   {:certname "host-2"
    :producer_timestamp (do (Thread/sleep 1) (time/now))
    :catalog_uuid "80a1f1d2-1bd3-4f68-86db-74b3d0d96f95"
    :inputs [["hiera" "puppetdb::globals::version"]
             ["hiera" "puppetdb::disable_ssl"]]}})

(defn stringify-timestamp [cmds]
  (map #(utils/update-when % [:producer_timestamp] time/to-string) cmds))

(defn cmds->expected-inputs [cmds]
  (mapcat (fn [cmd]
            (let [stamp (time/to-string (:producer_timestamp cmd))]
              (for [[type name] (:inputs cmd)]
                (-> cmd
                    (dissoc :inputs)
                    (assoc :type type :name name :producer_timestamp stamp)))))
          cmds))

(defn process-replace-inputs
  "Submits an appropriate replace catalog inputs for the provided map
  which must be the wire format data."
  [payload]
  (process-command!
   {:command (command-names :replace-catalog-inputs)
    :payload payload
    :received (time/now)
    :version command-version
    :certname (:certname payload)}
   *db*
   nil))

(defn validate-response-and-get-body [{:keys [status headers body]}]
  (is (= 200 status))
  (is (= {"Content-Type" "application/json; charset=utf-8"} headers))
  (-> body slurp (json/parse-string true)))
