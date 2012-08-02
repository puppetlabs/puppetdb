;; ## Utility functions related to HTTP

(ns com.puppetlabs.http
  (:require [ring.util.response :as rr]
            [cheshire.core :as json]))

(def HTTP-INTERNAL-ERROR java.net.HttpURLConnection/HTTP_INTERNAL_ERROR)

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
    (json-response body 200))
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
   400."
  ([error]
    (error-response error 400))
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
