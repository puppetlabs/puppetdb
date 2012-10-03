(ns com.puppetlabs.puppetdb.http.v2.command
  (:require [com.puppetlabs.puppetdb.http.v1.command :as v1-command]))

(def command-app
  v1-command/command-app)
