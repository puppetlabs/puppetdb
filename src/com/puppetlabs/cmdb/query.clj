(ns com.puppetlabs.cmdb.query
  (:require [clojure.contrib.logging :as log]
            [clojure.data.json :as json]
            clothesline.core
            [com.puppetlabs.cmdb.scf.storage :as scf-storage]
            com.puppetlabs.cmdb.query.resource))

;;;; Database connection pool and configuration.

;;;; Ring Handler and servlet lifecycle.
;;;;
;;;; The `ring-init` and `ring-destroy` hooks handle the initialization and
;;;; destruction of the ultimate servlet class used to hook to the rest of the
;;;; container environment.
;;;;
;;;; The `ring-handler` is defined here, as a product of the other code
;;;; defining the individual resources in the system, and their routes.
(defn ring-init
  "One-time initialization when the CMDB Query servlet is loaded into
the container."
  []
  (log/info "Initializing CMDB Query servlet")
  (scf-storage/initialize-connection-pool)
  (scf-storage/with-scf-connection
    (scf-storage/initialize-store)))

(defn ring-destroy
  "One-time cleanup when the CMDB Query servlet is stopped by the container."
  []
  (log/info "Destroying CMDB Query servlet")
  (scf-storage/shutdown-connection-pool))

(def
  ^{:doc "CMDB Query Ring handler entry-point.  This maps the top level namespace from
our Ring function to the individual control points used in the query system.

It is also responsible for the build-up and tear-down of middleware in the Ring
stack, delivering the API promises for the rest of our independent queries."}
  ring-handler
  (clothesline.core/produce-handler
   {"/resources" com.puppetlabs.cmdb.query.resource/resource-list-handler}))
