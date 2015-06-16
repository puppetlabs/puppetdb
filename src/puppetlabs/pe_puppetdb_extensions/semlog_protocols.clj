(ns puppetlabs.pe-puppetdb-extensions.semlog-protocols)

(defprotocol MarkerLogger
  (write-with-marker! [^org.slf4j.Logger logger level ^Throwable e msg marker]))
