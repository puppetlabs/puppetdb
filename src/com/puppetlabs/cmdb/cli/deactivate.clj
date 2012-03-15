(ns com.puppetlabs.cmdb.cli.deactivate
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-http.util :as util])
  (:use [com.puppetlabs.utils :only (cli! ini-to-map utf8-string->sha1)]))

(defn deactivate
  "Submits a 'deactivate node' request for `node` to the Grayskull instance
  specified by `host` and `port`."
  [node host port]
  (let [msg    (-> {:command "deactivate node"
                    :version 1
                    :payload (json/generate-string node)}
                 (json/generate-string))
        body   (format "checksum=%s&payload=%s"
                       (utf8-string->sha1 msg)
                       (util/url-encode msg))
        url    (format "http://%s:%s/commands" host port)
        result (client/post url {:body               body
                                 :throw-exceptions   false
                                 :content-type       :x-www-form-urlencoded
                                 :character-encoding "UTF-8"
                                 :accept             :json})]
    (if (not= 200 (:status result))
      (log/error result))))

(defn -main
  [& args]
  (let [[options nodes] (cli! args
                              ["-c" "--config" "Path to config.ini"])
        config      (ini-to-map (:config options))
        host        (get-in config [:jetty :host] "localhost")
        port        (get-in config [:jetty :port] 8080)]
    (doseq [node nodes]
      (log/info (str "Deactivating node " node))
      (deactivate node host port))))
