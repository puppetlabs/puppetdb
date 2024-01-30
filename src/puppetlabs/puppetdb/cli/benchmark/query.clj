(ns puppetlabs.puppetdb.cli.benchmark.query
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async :refer [<!! >!! alt! chan close! go go-loop timeout]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.puppetdb.utils :refer [println-err]])
  (:import
   (com.puppetlabs.ssl_utils SSLUtils)
   (java.io IOException)
   (java.net URI URLEncoder)
   (java.net.http HttpClient
                  HttpRequest
                  HttpRequest$BodyPublishers
                  HttpResponse$BodyHandlers)
   (java.nio.charset StandardCharsets)
   (java.time Duration)
   (java.util.function BiFunction)))

(defn- ssl-info->context
  [& {:keys [ssl-cert ssl-key ssl-ca-cert]}]
  (SSLUtils/pemsToSSLContext (io/reader ssl-cert)
                             (io/reader ssl-key)
                             (io/reader ssl-ca-cert)))

(defn- http-client [ssl-opts]
  (cond-> (HttpClient/newBuilder)
    ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
    ;;true (.connectTimeout (Duration/ofSeconds 10))
    (:ssl-cert ssl-opts) (.sslContext (ssl-info->context ssl-opts))
    true .build))

(defn- string-publisher [s] (HttpRequest$BodyPublishers/ofString s))
(defn- discarding-handler [] (HttpResponse$BodyHandlers/discarding))

(defn- as-java-bifn [f]
  (reify BiFunction
    (apply [_this x y] (f x y))))

(defn- go-http
  "Returns a channel that returns the query response or any exception
  and closes, doing all the work using the client's executor."
  [client request body-handler]
  ;; If you want a timeout, just set it when building the request
  ;; which will return an HttpTimeoutException.
  (let [result (chan)
        finish-async-http (fn finish-async-http [v ex]
                            (>!! result (or ex v))
                            (close! result))]
    (go
      (try
        (-> (.sendAsync client request body-handler)
            (.handleAsync (as-java-bifn finish-async-http)))
        (catch Throwable ex
          (finish-async-http nil ex))))
    result))

(defn- map->query-string
  [m]
  (when-not (seq m)
    (let [enc #(URLEncoder/encode % StandardCharsets/UTF_8)]
      (str/join \& (for [[k v] m] (str (enc k) "=" (enc v)))))))

(defn- query-spec->req
  [base-url {:keys [path query] :as spec} & {:keys [timeout]}]
  (let [uri (URI. (:protocol base-url) nil (:host base-url) (:port base-url) path
                  (map->query-string (select-keys spec [:limit :offset :order_by :query]))
                  nil)
        method (case (:method spec)
                 :get #(.GET %)
                 :post #(.POST % (-> {:query query} json/generate-string string-publisher)))]
    (cond-> (HttpRequest/newBuilder)
      ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
      true (.header "Content-Type" "application/json; charset=UTF-8")
      true (.header "Accept" "application/json")
      true (.uri uri)
      true method
      timeout (.timeout timeout)
      true .build)))

(defn- go-discarding-query [client base-url spec & {:as opts}]
  (go-http client (query-spec->req base-url spec opts) (discarding-handler)))

(defn send-random-queries
  [client base-url queries stop-ch]
  (go-loop []
    ;; A touch awkward because async limits recur positions
    (let [res-ch (go-discarding-query client base-url
                                      (rand-nth queries)
                                      {:timeout (Duration/ofSeconds 60)})]
      (when-let [res (alt! stop-ch false res-ch ([res] res))]
        (when (instance? IOException res)
          (println-err (trs "Response exception {0}" res)))
        (if (and res (alt! stop-ch false (timeout (* 1000 (rand-int 6))) true))
          (recur)
          true)))))

(defn cli [base-url ssl-opts queriers stop-ch]
  (let [queries (-> "puppetlabs/puppetdb/sample/queries.edn"
                    io/resource slurp edn/read-string)
        client (http-client ssl-opts)
        requested-categories (->> queriers (map #(-> % second keyword)) set)
        available-categories (-> queries keys set)
        unknown-categories (set/difference requested-categories available-categories)]
    (if (seq unknown-categories)
      (do
        (println-err "Error: unrecognized --querier categories:"
                     (str/join " " (sort unknown-categories)))
        2)
      (let [qs (for [[n category] queriers
                     :let [ck (keyword category)]
                     _ (range n)]
                 (do
                   (println-err "Starting" category "querier")
                   (send-random-queries client base-url (ck queries) stop-ch)))
            qs (-> qs flatten async/merge)
            finished (promise)]
        ;; Block jvm shutdown for up to 3s while we unwind
        (->> #(when (= :timeout (deref finished 3000 :timeout))
                (println-err (trs "Timed out while waiting for benchmark to stop.")))
             Thread. (.addShutdownHook (Runtime/getRuntime)))
        (while (<!! qs))
        (deliver finished true)
        0))))
