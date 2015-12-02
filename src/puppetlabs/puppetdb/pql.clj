(ns puppetlabs.puppetdb.pql
  (:require [clojure.string :refer [join]]
            [clojure.tools.logging :as log]
            [instaparse.core :as insta]
            [instaparse.failure :as failure]
            [instaparse.print :as print]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.pql.transform :as transform])
  (:import [com.fasterxml.jackson.core JsonParseException]))

(defn transform
  "Transform parsed PQL to AST."
  [tree]
  (insta/transform transform/transform-specification tree))

(def parse
  (insta/parser
   (clojure.java.io/resource "puppetlabs/puppetdb/pql/pql-grammar-experimental.ebnf")))

(defn pql->ast
  "Convert a PQL string to AST format."
  [pql]
  (log/warn "The syntax for PQL is still experimental, and may change in the future.")
  (transform (parse pql)))

(defn print-reason
  "Provides special case for printing negative lookahead reasons"
  [r]
  (cond
    (:NOT r)
    (format "NOT %s" (:NOT r))
    (:char-range r)
    (print/char-range->str r)
    (instance? java.util.regex.Pattern r)
    (print/regexp->str r)
    :else
    r))

(defn pprint-failure
  "Takes an augmented failure object and prints the error message"
  [{:keys [line column text reason]}]
  (let [opening (format "PQL parse error at line %d, column %d:\n\n%s\n%s\n\n"
                        line column text (failure/marker column))
        full-reasons (distinct (map :expecting
                                    (filter :full reason)))
        partial-reasons (distinct (map :expecting
                                       (filter (complement :full) reason)))
        total (+ (count full-reasons) (count partial-reasons))
        expected (cond (zero? total) nil
                       (= 1 total) "Expected:\n"
                       :else "Expected one of:\n\n")
        freasons (join (for [r full-reasons]
                         (format "%s (followed by end-of-string)" (print-reason r))))
        preasons (join
                  (for [r partial-reasons]
                    (format "%s\n" (print-reason r))))]
    (join [opening expected freasons preasons])))

(defn parse-json-or-pql-to-ast
  "Attempt to parse a string as JSON first, else fall back
  to parsing it as PQL."
  [query]
  (when query
    (if (re-find #"^\s*\[" query)
      (json/parse-strict-string query true)
      (let [pql-result (pql->ast query)]
        (if (map? pql-result)
          (throw (IllegalArgumentException. (pprint-failure pql-result)))
          (first pql-result))))))
