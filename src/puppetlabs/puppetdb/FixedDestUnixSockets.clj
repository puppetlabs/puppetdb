(ns puppetlabs.puppetdb.FixedDestUnixSockets
  (:import
   (jnr.unixsocket UnixSocket UnixSocketAddress UnixSocketChannel))
  (:gen-class
   :extends javax.net.SocketFactory
   :init init
   :constructors {[String] []}
   :state socketPath))

(defn fixed-destination-socket [path]
  (let [addr (UnixSocketAddress. path)]
    (proxy [UnixSocket] [(UnixSocketChannel/create)]
      (connect
        ([endpoint] (proxy-super connect addr))
        ([endpoint timeout] (proxy-super connect addr timeout))))))

(defn -init [^String path]
  [[] path])

(defn -createSocket
  ([this]
   (fixed-destination-socket (.socketPath this))))
