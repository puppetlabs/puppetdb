(ns puppetlabs.puppetdb.pql
  (:import com.fasterxml.jackson.core.JsonParseException)
  (:require [clojure.string :refer [join]]
            [instaparse.core :as insta]
            [instaparse.failure :as failure]
            [instaparse.print :as print]
            [puppetlabs.i18n.core :refer [tru]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.pql.transform :as transform]
            [puppetlabs.puppetdb.utils.string-formatter :refer [pprint-json-parse-exception]]))

(defn transform
  "Transform parsed PQL to AST."
  [tree]
  (insta/transform transform/transform-specification tree))

(def parse
  (insta/parser
   (clojure.java.io/resource "puppetlabs/puppetdb/pql/pql-grammar.ebnf")))

(defn pql->ast
  "Convert a PQL string to AST format."
  [pql]
  (transform (parse pql)))

(defn print-reason
  "Provides special case for printing negative lookahead reasons"
  [r]
  (cond
    (:NOT r)
    (format (tru "NOT {0}" (:NOT r)))
    (:char-range r)
    (print/char-range->str r)
    (instance? java.util.regex.Pattern r)
    (print/regexp->str r)
    :else
    r))

(defn pprint-failure
  "Takes an augmented failure object and prints the error message"
  [{:keys [line column text reason]}]
  (let [opening (tru "PQL parse error at line {0}, column {1}:\n\n{2}\n{3}\n\n"
                     line column text (failure/marker column))
        full-reasons (distinct (map :expecting
                                    (filter :full reason)))
        partial-reasons (distinct (map :expecting
                                       (filter (complement :full) reason)))
        total (+ (count full-reasons) (count partial-reasons))
        expected (cond (zero? total) nil
                       (= 1 total) (tru "Expected:\n")
                       :else (tru "Expected one of:\n\n"))
        freasons (join (for [r full-reasons]
                         (tru "{0} (followed by end-of-string)" (print-reason r))))
        preasons (join
                  (for [r partial-reasons]
                    (format "%s\n" (print-reason r))))]
    (join [opening expected freasons preasons])))

(defn parse-json-sequence
  "Parse a query string as JSON. Parse errors
   will result in an IllegalArgumentException"
  [query]
  (try
    (with-open [string-reader (java.io.StringReader. query)]
      (doall (json/parsed-seq string-reader)))
  (catch JsonParseException e
    (throw (IllegalArgumentException. (pprint-json-parse-exception e query))))))

(defn parse-json-query
  "Parse a query string as JSON. Multiple queries or any other
  data, after the query, will result in an IllegalArgumentException"
  [query]
  (when query
    (let [parsed-query (parse-json-sequence query)
          query-count (count parsed-query)]
      (if (= query-count 1)
        (first parsed-query)
        (throw (IllegalArgumentException.
                 (tru "Only one query may be sent in a request. You sent {0}." query-count)))))))

(defn parse-pql-query
  "Parse a query string as PQL. Parse errors will result in an
  IllegalArgumentException"
  [query]
  (let [pql-result (pql->ast query)]
    (if (map? pql-result)
      (throw (IllegalArgumentException.
              (pprint-failure pql-result)))
      (first pql-result))))

(defn parse-json-or-pql-to-ast
  "Parse a query string either as JSON or PQL to transform it to AST"
  [query]
  (when query
    (if (re-find #"^\s*\[" query)
      (parse-json-query query)
      (parse-pql-query query))))
