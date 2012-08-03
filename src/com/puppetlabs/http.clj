;; ## HTTP Utility library
;;
;; This namespace contains some http-related constants and utility
;; functions.

(ns com.puppetlabs.http
  (:require [ring.util.response :as rr]
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


;; ## HTTP/Ring utility functions

(defn acceptable-content-type
  "Returns a boolean indicating whether the `candidate` mime type
  matches any of those listed in `header`, an Accept header."
  [candidate header]
  {:pre [(string? candidate)]}
  (if-not (string? header)
    true
    (let [[prefix suffix] (.split candidate "/")
          wildcard        (str prefix "/*")
          types           (->> (clojure.string/split header #",")
                               (map #(.trim %))
                               (set))]
      (or (types wildcard)
          (types candidate)))))

(defn json-response
  "Returns a Ring response object with the supplied `body` and response `code`,
  and a JSON content type. If unspecified, `code` will default to 200."
  ([body]
     (json-response body status-ok))
  ([body code]
     (-> body
         (json/generate-string {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"})
         (rr/response)
         (rr/header "Content-Type" "application/json")
         (rr/status code))))

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
