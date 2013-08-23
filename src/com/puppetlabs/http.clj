;; ## HTTP Utility library
;;
;; This namespace contains some http-related constants and utility
;; functions.

(ns com.puppetlabs.http
  (:import [org.apache.http.impl EnglishReasonPhraseCatalog])
  (:require [com.puppetlabs.utils :as utils]
            [ring.util.response :as rr]
            [cheshire.core :as json]
            [clojure.reflect :as r]
            [clojure.string :as s]))


;; ## HTTP Status codes
;;
;; This section creates a series of variables representing legal HTTP
;; status codes. e.g. `status-ok` == 200, `status-bad-request` == 400,
;; etc.

(def http-constants
  (->> java.net.HttpURLConnection
       (r/reflect)
       (:members)
       (map :name)
       (map str)
       (filter #(.startsWith % "HTTP_"))))

(defn http-constant->sym
  "Convert the name a constant from the java.net.HttpURLConnection class into a
  symbol that we will use to define a Clojure constant."
  [name]
  (-> name
      (s/split #"HTTP_")
      (second)
      ((partial str "status-"))
      (.replace "_" "-")
      (.toLowerCase)
      (symbol)))

;; Define constants for all of the HTTP status codes defined in the
;; java class
(doseq [name http-constants]
  (let [key (http-constant->sym name)
        val (-> (.getField java.net.HttpURLConnection name)
                (.get nil))]
    (intern *ns* key val)))

(defmulti default-body
  "Provides a response body based on the status code of the resopnse.  The
  default body is based on a direct mapping between HTTP code (for instance:
  406) and a descriptive message for that status (for instance: Not
  Acceptable), as given in RFC 2616 section 10."
  (fn [request response] (:status response)))

(defmethod default-body status-bad-method
  [{:keys [request-method uri query-string]} response]
  (let [method (s/upper-case (name request-method))
        location (if query-string (format "%s?%s" uri query-string) uri)]
    (format "The %s method is not allowed for %s" method location)))

(defmethod default-body :default
  [request {:keys [status] :as response}]
  {:pre [status
         (>= status 100)
         (<= status 599)]}
  (.getReason EnglishReasonPhraseCatalog/INSTANCE status nil))

;; ## HTTP/Ring utility functions

(defn acceptable-content-type
  "Returns a boolean indicating whether the `candidate` mime type
  matches any of those listed in `header`, an Accept header."
  [candidate header]
  {:pre [(string? candidate)]}
  (if-not (string? header)
    true
    (let [[prefix suffix] (.split candidate "/")
          superwildcard   "*/*"
          wildcard        (str prefix "/*")
          types           (->> (s/split header #",")
                               (map #(.trim %))
                               (set))]
      (or (types superwildcard)
          (types wildcard)
          (types candidate)))))

(defn must-accept-type
  "Ring middleware that ensures that only requests with a given
  'Accept' header are let through. If no matching header is found,
  we return an HTTP 406."
  [f content-type]
  (fn [{:keys [headers] :as req}]
    (if (acceptable-content-type content-type (headers "accept"))
      (f req)
      (-> (format "must accept %s" content-type)
          (rr/response)
          (rr/status status-not-acceptable)))))

(defn json-response
  "Returns a Ring response object with the supplied `body` and response `code`,
  and a JSON content type. If unspecified, `code` will default to 200."
  ([body]
     (json-response body status-ok))
  ([body code]
     (utils/add-common-json-encoders!)
     (-> body
         (json/generate-string {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" :pretty true})
         (rr/response)
         (rr/header "Content-Type" "application/json")
         (rr/charset "utf-8")
         (rr/status code))))

(def json-response-content-type "application/json; charset=utf-8")

(defn error-response
  "Returns a Ring response object with the status code specified by `code`.
   If `error` is a Throwable, its message is used as the body of the response.
   Otherwise, `error` itself is used.  If unspecified, `code` will default to
   `status-bad-request`."
  ([error]
     (error-response error status-bad-request))
  ([error code]
     (let [msg (if (instance? Throwable error)
                 (.getMessage error)
                 (str error))]
       (-> msg
           (rr/response)
           (rr/status code)))))

(defn uri-segments
  "Converts the given URI into a seq of path segments. Empty segments
  (from a `//`, for example) are elided from the result"
  [uri]
  (remove #{""} (.split uri "/")))

(defn leading-uris
  "Given a URI, return a sequence of all the leading components of
  that URI.

  Example:
  (leading-uris \"/foo/bar/baz\")
  => [\"/foo\", \"/foo/bar\", \"/foo/bar/baz\"]

  (leading-uris \"/foo/bar/baz\" \"|\")
  => [\"|foo\", \"|foo|bar\", \"|foo|bar|baz\"]
"
  ([uri]
     (leading-uris uri "/"))
  ([uri delimiter]
     {:pre  [(.startsWith uri "/")]
      :post [(coll? %)]}
     (let [segments (uri-segments uri)
           f        (fn [[segs strs] u]
                      (let [segs' (conj segs u)]
                        [segs'
                         (conj strs (str delimiter (s/join delimiter segs')))]))]
       (second (reduce f [[] []] segments)))))

(defn parse-boolean-query-param
  "Utility method for parsing a query parameter whose value is expected to be
  a boolean.  In the case where the HTTP request contains the query parameter but
  not a value for it (e.g. `http://foo.com?mybool`), assumes the user intended
  to use the parameter as a flag, and thus return `true`.  If the key doesn't
  exist in the params map, return `false`.  In all other cases, attempt
  to parse the value of the param as a Boolean, and return the result."
  [params k]
  (if (contains? params k)
    (let [val (params k)]
      (cond
        ;; If the original query string contains the query param w/o a
        ;; a value, it will show up here as nil.  We assume that in that
        ;; case, the caller intended to use it as a flag.
        (= val nil)                  true
        (Boolean/parseBoolean val)   true
        :else                        false))
    false))
