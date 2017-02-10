(ns puppetlabs.puppetdb.integration.protocols)

(defprotocol TestServer
  (info-map [this]))
