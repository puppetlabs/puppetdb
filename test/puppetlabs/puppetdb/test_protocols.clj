(ns puppetlabs.puppetdb.test-protocols
  (:require  [clojure.test :refer :all]))

;; A protocol to be reified alongside IFn; after the function has been
;; called once, called? should return true.
(defprotocol IMockFn
  (called? [this]))
