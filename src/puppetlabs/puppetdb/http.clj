(ns puppetlabs.puppetdb.http
  (:require [puppetlabs.i18n.core :refer [tru trs]]
            [ring.util.response :as rr]
            [ring.util.io :as rio]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as s])
  (:import
   (java.io IOException Writer)
   (java.net HttpURLConnection)
   (org.apache.http.impl EnglishReasonPhraseCatalog)))

(defmulti default-body
  "Provides a response body based on the status code of the resopnse.  The
  default body is based on a direct mapping between HTTP code (for instance:
  406) and a descriptive message for that status (for instance: Not
  Acceptable), as given in RFC 2616 section 10."
  (fn [_request response] (:status response)))

(defmethod default-body HttpURLConnection/HTTP_BAD_METHOD
  [{:keys [request-method uri query-string]} _response]
  (let [method (s/upper-case (name request-method))
        location (if query-string (format "%s?%s" uri query-string) uri)]
    (format "The %s method is not allowed for %s" method location)))

(defmethod default-body :default
  [_request {:keys [status] :as _response}]
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
    (let [[prefix _] (.split candidate "/")
          superwildcard   "*/*"
          wildcard        (str prefix "/*")
          types           (->> (s/split header #",")
                               (map #(.trim %))
                               (set))]
      (or (types superwildcard)
          (types wildcard)
          (types candidate)))))

(def error-response-content-type "text/plain; charset=utf-8")

(defn simple-utf8-ctype? [type header-value]
  ;; https://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.7
  ;; This is not a strict validation, and it allows all whitespace,
  ;; not just linear whitespace and is completely case insensitive,
  ;; which for utf-8 is probably just fine.
  (= [type "charset=utf-8"]
     (map s/trim
          (-> header-value s/lower-case (s/split #";")))))

(defn json-utf8-ctype? [header-value]
  (simple-utf8-ctype? "application/json" header-value))

(defn error-ctype? [header-value]
  (simple-utf8-ctype? "text/plain" header-value))

(defn json-response*
  "Returns a Ring response object with the supplied `body`, response
  `code`, and a JSON content type and charset. `body` is assumed to
  alredy be JSON-ified. To auto-serialize body to JSON, look at
  `json-response`."
  ([body]
     (json-response* body HttpURLConnection/HTTP_OK))
  ([body code]
     (-> body
         rr/response
         (rr/content-type "application/json; charset=utf-8")
         (rr/status code))))

(defn upload-file!
  "Copies an octet-stream filetype upload to the local dir."
  [dir {:keys [tempfile filename]}]
  (let [local (io/file dir filename)]
    (io/copy tempfile local)
    local))

(defn tar-response
  [body filename]
  (-> body
      rr/response
      (rr/content-type "application/octet-stream")
      (rr/charset "utf-8")
      (rr/header "Content-Disposition" (str "attachment; filename=" filename))
      (rr/status HttpURLConnection/HTTP_OK)))

(defn streamed-tar-response
  [producer filename]
  (tar-response
   (rio/piped-input-stream producer)
   filename))

(defn json-response
  "Returns a Ring response object with the supplied `body` and response `code`,
  and a JSON content type. If unspecified, `code` will default to 200."
  ([body]
     (json-response body HttpURLConnection/HTTP_OK))
  ([body code]
     (-> body
         json/generate-pretty-string
         (json-response* code))))

(defn error-response
  "Returns a Ring response object with the status code specified by `code`.
   If `error` is a Throwable, its message is used as the body of the
  response.  Otherwise, `error` itself is used.  If unspecified,
  `code` will default to `HttpURLConnection/HTTP_BAD_REQUEST`."
  ([error]
     (error-response error HttpURLConnection/HTTP_BAD_REQUEST))
  ([error code]
   (log/debug error (trs "Caught HTTP processing exception"))
   (-> (if (instance? Throwable error)
         (.getMessage error)
         (str error))
       rr/response
       (rr/content-type error-response-content-type)
       (rr/status code))))

(defn denied-response
  [msg status]
  (-> (tru "Permission denied: {0}" msg)
      (error-response status)))

(defn must-accept-type
  "Ring middleware that ensures that only requests with a given
  'Accept' header are let through. If no matching header is found,
  we return an HTTP 406."
  [f content-type]
  (fn [{:keys [headers] :as req}]
    (if (acceptable-content-type content-type (headers "accept"))
      (f req)
      (error-response (tru "must accept {0}" content-type)
                      HttpURLConnection/HTTP_NOT_ACCEPTABLE))))

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

(defn stream-json
  "Serializes the supplied sequence to `buffer`, which is a `Writer`
  object."
  [coll buffer pretty-print]
  {:pre [(instance? Writer buffer)]}
  (if pretty-print
    (json/generate-pretty-stream coll buffer)
    (json/generate-stream coll buffer)))

(defmacro streamed-response
  "Evaluates `body` in a thread, with a local variable (`writer-var`)
  bound to a fresh, UTF-8 Writer object.

  Returns an InputStream. The InputStream is connected to the Writer
  by a pipe. Deadlock is prevented by executing `body` in a separate
  thread, therefore allowing `body` (the producer) to execute
  alongside the consumer of the returned InputStream.

  As `body` is executed in a separate thread, it's not possible for
  the caller to catch exceptions thrown by `body`. Errors are instead
  logged."
  {:deprecated true} ;; Drop with deprecated-produce-streaming-body
  [writer-var & body]
  `(rio/piped-input-stream
    (fn [ostream#]
      (with-open [~writer-var (io/writer ostream# :encoding "UTF-8")]
        (try
          (do ~@body)
          (catch IOException e#
            ;; IOException includes things like broken pipes due to
            ;; client disconnect, so no need to spam the log normally.
            (log/debug e# (trs "Error streaming response")))
          (catch Exception e#
            (log/error e# (trs "Error streaming response"))))))))

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
       (nil? val)                   true
       (Boolean/parseBoolean val)   true
       :else                        false))
    false))

(def header-map
  "Maps the legal keys from a puppetdb query response object to the
  corresponding HTTP header names."
  {:count "X-Records"})

(defn header-for-key
  "Given a key from a PuppetDB query response, returns the HTTP header that
  should be used in the HTTP response."
  [k]
  {:pre [(contains? header-map k)]
   :post [(string? %)]}
  (header-map k))

(defn add-headers
  "Given a Ring response and a PuppetDB query result map, returns
  an updated Ring response with the headers added."
  [response query-result]
  {:pre  [(map? query-result)]
   :post [(rr/response? %)]}
  (reduce
   (fn [r [k v]] (rr/header r (header-for-key k) v))
   response
   query-result))

(defn query-result-response
  "Given a PuppetDB query result map (as returned by `query/execute-query`),
  returns a Ring HTTP response object."
  [query-result]
  {:pre [(map? query-result)
         (contains? query-result :result)]
   :post [(rr/response? %)]}
  (-> (json-response (:result query-result))
      (add-headers (dissoc query-result :result))))

(defn status-not-found-response
  "Produces a json response for when an entity (catalog/nodes/environment/...) is not found."
  [type id]
  (json-response {:error (tru "No information is known about {0} {1}" type id)}
                 HttpURLConnection/HTTP_NOT_FOUND))

(defn bad-request-response
  "Produce a json 400 response with an :error key holding message."
  [message]
  (json-response {:error message} HttpURLConnection/HTTP_BAD_REQUEST))

(defn deprecated-app
  "Add an X-Deprecation warning for deprecated endpoints"
  [app msg request]
  (let [result (app request)]
    (log/warn msg)
    (rr/header result "X-Deprecation" msg)))

(defn experimental-warning
  "Add a Warning: header for experimental endpoints"
  [app msg]
  (fn [request]
    (let [result (app request)]
      (log/warn msg)
      (rr/header result "Warning" msg))))
