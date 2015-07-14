(ns puppetlabs.pe-puppetdb-extensions.sync.simulation-test
  (:refer-clojure :exclude [sync])
  (:require [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.pprint :refer :all]))

;;; System model

(def empty-pdb
  {:time 0
   :catalog-producer-timestamp nil
   :node-expired-timestamp nil})

(def initial-system
  {:pdb-x empty-pdb
   :pdb-y empty-pdb})

;; Just a constant, since we're not too concerned about differing node-ttl values
(def node-ttl 10)


;;; Command generators

(def gen-pdb (gen/one-of [(gen/return :pdb-x) (gen/return :pdb-y)]))
(def gen-timestamp gen/nat)

(def gen-tick-command
  (gen/hash-map :command (gen/return :tick)
                :host gen-pdb))

(def gen-tick-until-node-expires
  (gen/hash-map :command (gen/return :tick-until-node-expires)
                :host gen-pdb))

(def gen-replace-catalog-command
  (gen/hash-map :command (gen/return :replace-catalog)
                :host gen-pdb
                :catalog-producer-timestamp gen-timestamp))

(def gen-sync-command
  (gen/hash-map :command (gen/return :sync)
                :to gen-pdb))

(def gen-command
  (gen/one-of [gen-tick-command
               gen-tick-until-node-expires
               gen-replace-catalog-command
               gen-sync-command]))


;;; System simulation

(defn would-be-expired-locally [time catalog-producer-timestamp]
  (< catalog-producer-timestamp (- time node-ttl)))

(defn node-expired? [{:keys [node-expired-timestamp time]}]
  (and node-expired-timestamp
       (<= node-expired-timestamp time) true))

(defn merge-pdbs [local remote]
  (let [local-catalog-time (:catalog-producer-timestamp local)
        remote-catalog-time (:catalog-producer-timestamp remote)]
    (cond
      (nil? remote-catalog-time)
      local

      (would-be-expired-locally (:time local) remote-catalog-time)
      local

      (or (nil? local-catalog-time)
          (< local-catalog-time remote-catalog-time))
      (assoc local
             :catalog-producer-timestamp remote-catalog-time
             :node-expired-timestamp nil)

      :else
      local)))

(defn expire-node-if-needed [{:keys [time catalog-producer-timestamp] :as p}]
  (cond
    (nil? catalog-producer-timestamp)
    p

    (would-be-expired-locally time catalog-producer-timestamp)
    (assoc p :node-expired-timestamp time)

    :else
    p))

(defn tick-and-expire [system host]
  (-> system
      (update-in [host :time] inc)
      (update host expire-node-if-needed)))

(defn other-pdb [pdb]
  (if (= pdb :pdb-x)
    :pdb-y
    :pdb-x))

;;; Command handlers

(defmulti handle-command (fn [system command] (:command command)))

(defmethod handle-command :tick [system {:keys [host]}]
  (tick-and-expire system host))

(defmethod handle-command :tick-until-node-expires [system {:keys [host]}]
  (loop [s system]
   (cond
     (nil? (get-in s [host :catalog-producer-timestamp])) s ; no catalog means no node to expire
     (get-in s [host :node-expired-timestamp]) s
     :else (recur (tick-and-expire s host)))))

(defmethod handle-command :replace-catalog [system {:keys [host catalog-producer-timestamp]}]
  (update-in system [host :catalog-producer-timestamp]
             #(if %
                (max % catalog-producer-timestamp)
                catalog-producer-timestamp)))

(defmethod handle-command :sync [system {:keys [to]}]
  (let [from (other-pdb to)]
    (update system to merge-pdbs (get system from))))

;;; Property test

(defn simulate [commands]
  (reduce handle-command initial-system commands))

(def sync-commands [{:command :sync, :to :pdb-x}
                    {:command :sync, :to :pdb-y}])

(defn simulate-and-sync [commands]
  (simulate (concat commands sync-commands)))

(defn system-is-mostly-consistent? [s]
  (or (= (-> s :pdb-x :catalog-producer-timestamp)
         (-> s :pdb-y :catalog-producer-timestamp))
      (node-expired? (:pdb-x s))
      (node-expired? (:pdb-y s))))

(defspec ^:simulation test-mostly-consistent
  1000
  (prop/for-all [command-vec (gen/vector gen-command)]
    (let [final-state (simulate-and-sync command-vec)]
      (system-is-mostly-consistent? final-state))))

;;; Expiration check test

;; In systems where:
;;
;; x is not expired
;; x's catalog-producer-timestamp + node-ttl <= y's current time
;; y is expired
;;
;; ... a naive sync algorithm could cause y to be re-activated when it
;; shouldn't. To fix this, we only pull data if it would not be expired locally.
;; Here we test that conditional, showing that y remains active after syncing
;; with x.
;;
;;            X               Y
;;            ------------------------------
;;            t6,c1         c2,t14,expired
;;            t6,c2 <-sync- c2,t14,expired
;; -catalog-> t6,c3         c2,t14,expired
;;            t6,c3 -sync-> c2,t14,*expired*
(deftest ^:simulation expired-node-doesnt-spontaneously-reactivate
  (let [initial-system {:pdb-x {:time 6
                                :catalog-producer-timestamp 1
                                :node-expired-timestamp nil}
                        :pdb-y {:time 14
                                :catalog-producer-timestamp 2
                                :node-expired-timestamp 12}}
        commands [{:command :sync, :to :pdb-x}
                  {:command :replace-catalog, :host :pdb-x, :catalog-producer-timestamp 3}
                  {:command :sync, :to :pdb-y}]
        final-system (reduce handle-command initial-system commands)]
    (is (-> final-system :pdb-y :node-expired-timestamp))))
